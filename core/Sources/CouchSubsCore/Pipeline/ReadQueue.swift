// SPDX-License-Identifier: AGPL-3.0-only

import Foundation

public enum ReadQueueError: Error, Equatable {
    /// Okuma `ms` milisaniyede bitmedi; kare atlandı.
    case timedOut(ms: Int)
    /// Okuyucu art arda takıldı ve takılan okumalar hâlâ bitmedi; yeni okuma verilmedi (#507).
    case stuck
}

/// Yazı tanıma (Vision) çağrılarının tek sırası (#385, #507).
///
/// Vision'ın okuması senkrondur ve bazen hiç dönmez. Eskiden okumalar `Task.detached` içinde, Swift Concurrency'nin
/// ortak havuzunda yapılıyordu: takılan her okuma havuzun bir thread'ini sonsuza dek tutuyor, `FrameGate` her 15
/// saniyede hattı yeniden kurup bir okuma daha veriyordu. Havuz (çekirdek sayısı kadar thread) dolunca uygulamadaki
/// bütün `await`'ler duruyor, "Çeviriyi başlat" gri kalıyordu.
///
/// Burada okumalar kendi seri `DispatchQueue`'sunda çalışır; çağıran `await` ile bekler ve havuzu tutmaz. Süreç
/// genelinde aynı anda tek okuma verilir (altyazı, ek alanlar, ekranı çevir): Vision'ın metin tanıma kuyruğu
/// (`VNCRImageReaderDetector`) seridir ve aynı anda verilen istekler onu kilitleyebiliyordu.
///
/// `timeoutMs` içinde bitmeyen okuma `ReadQueueError.timedOut` ile döner, kare atlanır. Takılan okuma kuyruğu tuttuğu
/// için okuyucu yeni bir kuyrukla yeniden kurulur; sonraki kareler oradan okunur. Okuyucu en çok `maxStuck` kez yeniden
/// kurulur (takılan her okuma bir thread tutar); sonra da takılırsa, takılanlar bitene kadar okuma hemen
/// `ReadQueueError.stuck` ile döner, kuyrukta birikmez.
public final class ReadQueue: @unchecked Sendable {
    public static let shared = ReadQueue()
    /// Bütün karenin derin okuması (üç Vision isteği) yavaş Mac'te birkaç saniye sürer; `FrameGate`'in 15 saniyesinden
    /// önce dönmeli ki hat yeniden kurulmadan sonraki kare okunsun.
    public static let defaultTimeoutMs = 10_000
    public static let defaultMaxStuck = 3

    private final class Lane: @unchecked Sendable {
        let queue: DispatchQueue
        /// Bu kuyrukta süresi dolduğu hâlde hâlâ süren okumalar.
        var stuck = 0
        init(label: String) { queue = DispatchQueue(label: label, qos: .userInitiated) }
    }

    /// Tek okuma: sonucu ya da süre aşımı, hangisi önce gelirse bir kez teslim edilir.
    private final class Call<T: Sendable>: @unchecked Sendable {
        enum State { case waiting, running, done }
        private let lock = NSLock()
        private var state = State.waiting
        private var continuation: CheckedContinuation<Result<T, Error>, Never>?

        init(_ continuation: CheckedContinuation<Result<T, Error>, Never>) { self.continuation = continuation }

        /// Okuma başlıyor; süresi önceden dolduysa false (başlatılmaz).
        func begin() -> Bool {
            lock.withLock {
                guard state == .waiting else { return false }
                state = .running
                return true
            }
        }

        /// Okuma bitti. Süre önceden dolmuşsa false.
        func finish(_ result: Result<T, Error>) -> Bool {
            let pending: CheckedContinuation<Result<T, Error>, Never>? = lock.withLock {
                defer { state = .done; continuation = nil }
                return continuation
            }
            pending?.resume(returning: result)
            return pending != nil
        }

        /// Süre doldu. Okuma o anda sürüyorsa (thread'i takılıysa) önce `whenStuck` çalışır, sonra çağırana hata döner;
        /// okuma zaten bittiyse bir şey olmaz.
        func expire(_ error: Error, whenStuck: () -> Void) {
            let (pending, wasRunning): (CheckedContinuation<Result<T, Error>, Never>?, Bool) = lock.withLock {
                let wasRunning = state == .running
                let pending = continuation
                continuation = nil
                if state == .waiting { state = .done }
                return (pending, wasRunning)
            }
            guard let pending else { return }
            // Yeni kuyruk, çağıran bir sonraki okumayı vermeden kurulur.
            if wasRunning { whenStuck() }
            pending.resume(returning: .failure(error))
        }
    }

    private let label: String
    private let lock = NSLock()
    private var lane: Lane
    private var generation = 0
    private var stuckTotal = 0
    private let timeoutMs: Int
    private let maxStuck: Int
    /// Okuyucu yeniden kuruldu (takılan okuma kuyrukta bırakıldı); değer, takılı okuma sayısı.
    public var onRebuild: (@Sendable (Int) -> Void)?

    public init(label: String = "com.couchsubs.ocr", timeoutMs: Int = ReadQueue.defaultTimeoutMs, maxStuck: Int = ReadQueue.defaultMaxStuck) {
        self.label = label
        self.timeoutMs = timeoutMs
        self.maxStuck = maxStuck
        lane = Lane(label: "\(label).0")
    }

    /// Takılı (süresi dolup hâlâ süren) okuma sayısı.
    public var stuckCount: Int { lock.withLock { stuckTotal } }

    /// `work`'ü okuma sırasında çalıştırır. Çağıranın thread'i (Swift havuzu) beklerken bloklanmaz.
    public func run<T: Sendable>(_ work: @escaping @Sendable () throws -> T) async throws -> T {
        let current: Lane? = lock.withLock { lane.stuck > 0 ? nil : lane }
        guard let current else { throw ReadQueueError.stuck }
        let timeoutMs = self.timeoutMs
        let result: Result<T, Error> = await withCheckedContinuation { continuation in
            let call = Call<T>(continuation)
            current.queue.async { [self] in
                guard call.begin() else { return }
                let result = Result { try work() }
                if !call.finish(result) { released(current) }
            }
            DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + .milliseconds(timeoutMs)) { [self] in
                call.expire(ReadQueueError.timedOut(ms: timeoutMs)) { abandoned(current) }
            }
        }
        return try result.get()
    }

    /// Süresi dolan okuma hâlâ sürüyor: kuyruğu tutuyor. Sınır aşılmadıysa yeni kuyruk kurulur.
    private func abandoned(_ stuckLane: Lane) {
        let rebuilt: Int? = lock.withLock {
            stuckLane.stuck += 1
            stuckTotal += 1
            guard stuckLane === lane, stuckTotal <= maxStuck else { return nil }
            generation += 1
            lane = Lane(label: "\(label).\(generation)")
            return stuckTotal
        }
        if let rebuilt { onRebuild?(rebuilt) }
    }

    /// Takılan okuma sonunda bitti; kuyruğu yeniden kullanılabilir.
    private func released(_ stuckLane: Lane) {
        lock.withLock {
            stuckLane.stuck -= 1
            stuckTotal -= 1
        }
    }
}
