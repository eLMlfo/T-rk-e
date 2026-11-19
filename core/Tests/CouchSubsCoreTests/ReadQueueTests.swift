// SPDX-License-Identifier: AGPL-3.0-only
#if canImport(CoreGraphics)
import CoreGraphics
import XCTest
@testable import CouchSubsCore

/// Test sonunda serbest bırakılan, o zamana dek dönmeyen okuma (takılan Vision çağrısı gibi).
private final class Hang: @unchecked Sendable {
    private let semaphore = DispatchSemaphore(value: 0)
    private let lock = NSLock()
    private var waiting = 0
    func wait() {
        lock.withLock { waiting += 1 }
        semaphore.wait()
    }
    func release() {
        let count = lock.withLock { waiting }
        for _ in 0..<count + 64 { semaphore.signal() }
    }
}

/// İlk okuması takılır, sonrakiler yazı döndürür.
private final class FirstHangsReader: TextReader, @unchecked Sendable {
    let hang = Hang()
    private let lock = NSLock()
    private var calls = 0
    var callCount: Int { lock.withLock { calls } }
    func read(_ image: CGImage) throws -> String {
        let call = lock.withLock { calls += 1; return calls }
        if call == 1 { hang.wait() }
        return "Hello there."
    }
}

private final class NullSender: SubtitleSending {
    func sendSubtitle(_ text: String, durationMs: Int, box: TextBox?, replaces: Bool) {}
    func clear() {}
}

private final class EchoTranslator: TextTranslator {
    func translate(_ text: String) async throws -> String { "Çeviri: " + text }
}

/// #507: takılan Vision okuması Swift havuzunu doldurup "Çeviriyi başlat"ı kilitliyordu.
final class ReadQueueTests: XCTestCase {
    func testTimedOutReadIsSkippedAndNextReadRunsOnRebuiltQueue() async throws {
        let hang = Hang()
        defer { hang.release() }
        let queue = ReadQueue(label: "test.timeout", timeoutMs: 150)
        let rebuilds = Counter()
        queue.onRebuild = { _ in rebuilds.add() }

        do {
            _ = try await queue.run { hang.wait(); return 1 }
            XCTFail("takılan okuma dönmemeliydi")
        } catch {
            XCTAssertEqual(error as? ReadQueueError, .timedOut(ms: 150))
        }
        let next = try await queue.run { 2 }
        XCTAssertEqual(next, 2)
        XCTAssertEqual(rebuilds.value, 1)
        XCTAssertEqual(queue.stuckCount, 1)
    }

    func testStuckReadsDoNotStarveSwiftPool() async throws {
        let hang = Hang()
        defer { hang.release() }
        let queue = ReadQueue(label: "test.pool", timeoutMs: 100, maxStuck: 2)
        // Çekirdek sayısının üç katı takılan okuma: eskiden her biri havuzdan bir thread tutardı.
        let count = ProcessInfo.processInfo.activeProcessorCount * 3
        await withTaskGroup(of: Void.self) { group in
            for _ in 0..<count {
                group.addTask { _ = try? await queue.run { hang.wait() } }
            }
        }
        // Havuz serbest: başka bir iş (AppModel.start'taki await'ler gibi) hemen devam eder.
        let pool = expectation(description: "Swift havuzu cevap veriyor")
        Task.detached { _ = await Task.detached { 42 }.value; pool.fulfill() }
        await fulfillment(of: [pool], timeout: 5)

        // Her yeni kuyruk da takılırsa sınırda durulur: takılanlar bitene kadar okuma hemen döner, bekletmez.
        for _ in 0..<4 { _ = try? await queue.run { hang.wait() } }
        XCTAssertEqual(queue.stuckCount, 3)
        do {
            _ = try await queue.run { 1 }
            XCTFail("sınırda okuma verilmemeliydi")
        } catch {
            XCTAssertEqual(error as? ReadQueueError, .stuck)
        }
        // Takılan okumalar bitince okuyucu yeniden çalışır.
        hang.release()
        var value: Int?
        for _ in 0..<100 where value == nil {
            value = try? await queue.run { 3 }
            if value == nil { try await Task.sleep(for: .milliseconds(20)) }
        }
        XCTAssertEqual(value, 3)
    }

    func testFallbackReadsRunOneAtATime() throws {
        let image = CGContext(data: nil, width: 200, height: 50, bitsPerComponent: 8, bytesPerRow: 0,
                              space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!.makeImage()!
        let tracker = Concurrency()
        let reading = try VisionReader.firstLines(of: image) { _ in
            tracker.enter()
            Thread.sleep(forTimeInterval: 0.05)
            tracker.leave()
            return .empty
        }
        XCTAssertTrue(reading.lines.isEmpty)
        XCTAssertEqual(tracker.calls, 3)
        XCTAssertEqual(tracker.maxAtOnce, 1)
    }

    @MainActor
    func testPipelineKeepsReadingAfterHungRead() async {
        let reader = FirstHangsReader()
        defer { reader.hang.release() }
        let pipeline = SubtitlePipeline(reader: reader, translator: EchoTranslator(), server: NullSender(),
                                        readQueue: ReadQueue(label: "test.pipeline", timeoutMs: 150))
        var results: [Error?] = []
        pipeline.onReadResult = { results.append($0) }
        let image = CGContext(data: nil, width: 64, height: 64, bitsPerComponent: 8, bytesPerRow: 0,
                              space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!.makeImage()!

        await pipeline.process(image, region: CropRegion())
        await pipeline.process(image, region: CropRegion())
        await pipeline.process(image, region: CropRegion())

        XCTAssertEqual(results.count, 3)
        XCTAssertEqual(results.first.flatMap { $0 as? ReadQueueError }, .timedOut(ms: 150))
        XCTAssertNil(results.last ?? nil)
        XCTAssertEqual(reader.callCount, 3)
    }
}

private final class Counter: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0
    func add() { lock.withLock { count += 1 } }
    var value: Int { lock.withLock { count } }
}

private final class Concurrency: @unchecked Sendable {
    private let lock = NSLock()
    private var now = 0
    private(set) var calls = 0
    private(set) var maxAtOnce = 0
    func enter() { lock.withLock { now += 1; calls += 1; maxAtOnce = max(maxAtOnce, now) } }
    func leave() { lock.withLock { now -= 1 } }
}
#endif
