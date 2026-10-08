import Dispatch
import os

/// The outcome of an asynchronous fetch that was waited for up to a deadline.
public enum FetchOutcome<Value: Sendable>: Sendable {
	case fetched(Value)
	case failed(any Error)
	case timedOut

	private struct NoResult: Error {}

	/// Starts a fetch and blocks the calling thread until its completion handler runs or the timeout passes. A completion after the timeout is dropped.
	///
	/// - Parameter fetch: Starts the fetch and calls the handler it is given once, with a value or an error.
	public init(waitingAtMost timeout: DispatchTimeInterval, for fetch: (@escaping @Sendable (Value?, (any Error)?) -> Void) -> Void) {
		// the handler holds on to these, so that a completion after the timeout writes into them harmlessly
		let outcome = OSAllocatedUnfairLock<Self?>(initialState: nil)
		let completed = DispatchSemaphore(value: 0)
		fetch { value, error in
			outcome.withLock { $0 = value.map(Self.fetched) ?? .failed(error ?? NoResult()) }
			completed.signal()
		}
		guard completed.wait(timeout: .now() + timeout) == .success, let fetched = outcome.withLock({ $0 }) else {
			self = .timedOut
			return
		}
		self = fetched
	}
}
