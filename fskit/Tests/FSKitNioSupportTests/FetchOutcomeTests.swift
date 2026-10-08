import Foundation
import os
import Testing
@testable import FSKitNioSupport

struct FetchOutcomeTests {
	private static let timeout = DispatchTimeInterval.milliseconds(200)

	private struct FetchError: Error {}

	/// Waits for a fetch on a thread of its own and returns the outcome.
	///
	/// When the wait has not returned well after its deadline, this records an issue and returns `nil`, leaving the thread behind. A wait that ignores its deadline thus fails its test, which `.timeLimit` could not do, since it cannot end a thread blocked on a semaphore.
	private func outcome(of fetch: @escaping @Sendable (@escaping @Sendable ([Int]?, (any Error)?) -> Void) -> Void) -> FetchOutcome<[Int]>? {
		let outcome = OSAllocatedUnfairLock<FetchOutcome<[Int]>?>(initialState: nil)
		let returned = DispatchSemaphore(value: 0)
		Thread.detachNewThread {
			let waited = FetchOutcome(waitingAtMost: Self.timeout, for: fetch)
			outcome.withLock { $0 = waited }
			returned.signal()
		}
		guard returned.wait(timeout: .now() + Self.timeout + .seconds(2)) == .success else {
			Issue.record("the wait did not return after its deadline")
			return nil
		}
		return outcome.withLock { $0 }
	}

	@Test func returnsTheFetchedValue() {
		#expect(outcome { $0([1, 2], nil) }?.value == [1, 2])
	}

	@Test func returnsTheError() {
		#expect(outcome { $0(nil, FetchError()) }?.error is FetchError)
	}

	@Test func timesOutWhenTheFetchNeverCompletes() {
		#expect(outcome { _ in }?.isTimedOut == true)
	}

	@Test func dropsACompletionAfterTheDeadline() {
		let completedLate = DispatchSemaphore(value: 0)

		let outcome = outcome { completion in
			Thread.detachNewThread {
				Thread.sleep(forTimeInterval: 0.5)
				completion([1], nil)
				completedLate.signal()
			}
		}

		#expect(outcome?.isTimedOut == true)
		#expect(completedLate.wait(timeout: .now() + 5) == .success)
	}
}

private extension FetchOutcome {
	var value: Value? {
		if case let .fetched(value) = self {
			value
		} else {
			nil
		}
	}

	var error: (any Error)? {
		if case let .failed(error) = self {
			error
		} else {
			nil
		}
	}

	var isTimedOut: Bool {
		if case .timedOut = self {
			true
		} else {
			false
		}
	}
}
