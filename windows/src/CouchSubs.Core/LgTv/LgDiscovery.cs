// SPDX-License-Identifier: AGPL-3.0-only

using System.Net;
using System.Net.Sockets;
using System.Text;

namespace CouchSubs.Core.LgTv;

public enum TvBrand
{
    Lg,
    Samsung,
    /// <summary>Android TV: bilgisayar TV'yi denetlemez, TV uygulaması bilgisayarı kendisi bulur.</summary>
    AndroidTv,
}

/// <param name="Brand">Eski kayıtlarda marka yoktur; hepsi LG'dir.</param>
public sealed record LgTv(string Name, string Host, TvBrand Brand = TvBrand.Lg);

/// <summary>Yerel ağdaki LG webOS TV'leri SSDP ile bulur.</summary>
public static class LgDiscovery
{
    internal const string SearchTarget = "urn:lge-com:service:webos-second-screen:1";
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(2) };

    /// <summary>Yedek arama: webOS 3.x (2017) TV'ler ikinci ekran hedefine cevap vermiyor, "ssdp:all"a veriyor (#498).</summary>
    internal const string AllTarget = "ssdp:all";

    public static async Task<IReadOnlyList<LgTv>> SearchAsync(TimeSpan? timeout = null, CancellationToken cancellationToken = default)
    {
        var wait = timeout ?? TimeSpan.FromSeconds(3);
        var hosts = await CollectResponses(SearchTarget, wait, cancellationToken);
        // Cevap yoksa eski webOS için "ssdp:all": hem çok noktaya yayınla hem ağdaki her adrese tek tek (o TV yalnız
        // doğrudan gönderilen aramaya cevap verdi).
        if (hosts.Count == 0)
            hosts = await CollectResponses(AllTarget, wait, cancellationToken,
                LocalNetwork.ScanTargets(LocalNetwork.PrivateAddresses()).ToList());
        var tvs = new List<LgTv>();
        foreach (var host in hosts.Distinct().Order(StringComparer.Ordinal))
            tvs.Add(new LgTv(await FriendlyName(host) ?? "LG TV", host));
        return tvs;
    }

    /// <summary>
    /// Elle yazılan adresteki TV: Samsung 8001'de kendini tanıtırsa Samsung, değilse LG sayılır (webOS 3 cihaz tanımını
    /// her zaman vermiyor; adres yanlışsa Bağlan "TV yanıt vermedi" der).
    /// </summary>
    public static async Task<LgTv> TvAtAsync(string host, CancellationToken cancellationToken = default)
    {
        if (await SamsungDiscovery.DeviceNameAsync(host, cancellationToken) is { } samsung)
            return new LgTv(samsung, host, TvBrand.Samsung);
        return new LgTv(await FriendlyName(host) ?? "LG TV", host);
    }

    /// <summary>
    /// Elle yazılan TV adresi (Mac/telefon: ManualAddress): boşluk, "http://", port ve yol atılır, virgül noktaya döner.
    /// Yalnız IPv4 kabul edilir; değilse null.
    /// </summary>
    public static string? NormalizeHost(string? input)
    {
        var text = (input ?? "").Trim().Replace(',', '.');
        var scheme = text.IndexOf("://", StringComparison.Ordinal);
        if (scheme >= 0) text = text[(scheme + 3)..];
        var end = text.IndexOfAny(['/', '?', '#', ':']);
        if (end >= 0) text = text[..end];
        text = text.TrimEnd('.');
        var parts = text.Split('.');
        if (parts.Length != 4) return null;
        var values = new List<string>();
        foreach (var part in parts)
        {
            if (part.Length is < 1 or > 3 || !part.All(char.IsAsciiDigit)) return null;
            var value = int.Parse(part, System.Globalization.CultureInfo.InvariantCulture);
            if (value > 255) return null;
            values.Add(value.ToString(System.Globalization.CultureInfo.InvariantCulture));
        }
        return string.Join('.', values);
    }

    /// <summary>
    /// SSDP yanıtından TV'nin adresini çıkarır; aranan hedef değilse null. "ssdp:all" aramasında her cihaz cevap verir:
    /// yalnız kendini LG webOS TV olarak tanıtan yanıt (bkz. IsLgHeader) alınır.
    /// </summary>
    public static string? HostFromSsdpResponse(string response, string searchTarget = SearchTarget)
    {
        string? location = null;
        var isLg = false;
        foreach (var line in response.Split("\r\n"))
        {
            var colon = line.IndexOf(':');
            if (colon < 0) continue;
            var name = line[..colon].Trim().ToUpperInvariant();
            var value = line[(colon + 1)..].Trim();
            if (name == "LOCATION") { location = value; continue; }
            if (name is "ST" or "NT" && value == searchTarget) isLg = true;
            if (searchTarget == AllTarget && name is "ST" or "NT" or "USN" or "SERVER" && IsLgHeader(value)) isLg = true;
        }
        return isLg && Uri.TryCreate(location, UriKind.Absolute, out var url) ? url.Host : null;
    }

    /// <summary>
    /// LG webOS TV'nin SSDP başlığı mı: sunucu adında "webOS" (webOS 3: "... LGE WebOS TV ...") ya da cihaz kimliğinde
    /// "LGTV" (webOS 3: "uuid:LGTV2017..."). LG'nin TV olmayan cihazları sayılmaz.
    /// </summary>
    internal static bool IsLgHeader(string value) =>
        value.Contains("webos", StringComparison.OrdinalIgnoreCase) || value.Contains("lgtv", StringComparison.OrdinalIgnoreCase);

    /// <param name="unicast">Verilirse istek ayrıca bu adreslerin her birine tek tek gönderilir.</param>
    internal static async Task<List<string>> CollectResponses(string searchTarget, TimeSpan timeout, CancellationToken cancellationToken,
        IReadOnlyList<string>? unicast = null)
    {
        var hosts = new List<string>();
        using var udp = new UdpClient(AddressFamily.InterNetwork);
        var request = Encoding.ASCII.GetBytes(
            $"M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: {searchTarget}\r\n\r\n");
        var target = new IPEndPoint(IPAddress.Parse("239.255.255.250"), 1900);
        // İstek her ağ bağdaştırıcısından ayrı gönderilir: varsayılan bağdaştırıcı VPN ya da sanal ağ (Hyper-V, WSL)
        // olabilir ve TV o ağda değildir. Yanıtlar bu soketin portuna tek tek gelir.
        var interfaces = LocalNetwork.PrivateAddresses();
        for (var i = 0; i < 2; i++)
        {
            if (interfaces.Count == 0) await udp.SendAsync(request, target, cancellationToken);
            foreach (var local in interfaces)
            {
                try
                {
                    udp.Client.SetSocketOption(SocketOptionLevel.IP, SocketOptionName.MulticastInterface, local.GetAddressBytes());
                    await udp.SendAsync(request, target, cancellationToken);
                }
                catch (SocketException) { /* bu bağdaştırıcıdan gönderilemedi; diğerleri denenir */ }
            }
        }
        foreach (var host in unicast ?? [])
        {
            var direct = Encoding.ASCII.GetBytes(
                $"M-SEARCH * HTTP/1.1\r\nHOST: {host}:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: {searchTarget}\r\n\r\n");
            try { await udp.SendAsync(direct, new IPEndPoint(IPAddress.Parse(host), 1900), cancellationToken); }
            catch (SocketException) { /* bu adrese gönderilemedi */ }
        }

        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(timeout);
        try
        {
            while (true)
            {
                var result = await udp.ReceiveAsync(deadline.Token);
                if (HostFromSsdpResponse(Encoding.UTF8.GetString(result.Buffer), searchTarget) is { } host) hosts.Add(host);
            }
        }
        catch (OperationCanceledException) { }
        return hosts;
    }

    /// <summary>TV'nin ağdaki adını (ör. "[LG] webOS TV UK7550") cihaz tanımından okur.</summary>
    private static async Task<string?> FriendlyName(string host)
    {
        // webOS cihaz tanımı 1990 numaralı portta yayınlanır; okunamazsa genel ad kullanılır.
        try
        {
            var xml = await Http.GetStringAsync($"http://{host}:1990/");
            var start = xml.IndexOf("<friendlyName>", StringComparison.Ordinal);
            var end = start < 0 ? -1 : xml.IndexOf("</friendlyName>", start, StringComparison.Ordinal);
            return end < 0 ? null : xml[(start + "<friendlyName>".Length)..end];
        }
        catch
        {
            return null;
        }
    }
}
