import Dispatch
import os

/// The outcome of an asynchronous fetch that was waited for up to a deadline.
public enum FetchOutcome<Value: Sendable>: Sendable {
	case fetched(Value)
	case failed(any Error)
	case timedOut

	private struct NoResult: Error {}

	private enum State: Sendable {
		case waiting
		case completed(FetchOutcome)
		/// The wait timed out before the fetch completed.
		case abandoned
	}

	/// Starts a fetch and blocks the calling thread until its completion handler runs or the timeout passes. A late error is dropped.
	///
	/// - Parameters:
	///   - lateValue: Receives the value of a fetch that completes after the timeout, which no caller received.
	///   - fetch: Starts the fetch and calls the handler it is given once, with a value or an error.
	public init(waitingAtMost timeout: DispatchTimeInterval, lateValue: (@Sendable (Value) -> Void)? = nil, for fetch: (@escaping @Sendable (Value?, (any Error)?) -> Void) -> Void) {
		let state = OSAllocatedUnfairLock(initialState: State.waiting)
		let completed = DispatchSemaphore(value: 0)
		fetch { value, error in
			let late = state.withLock { state -> Value? in
				guard case .abandoned = state else {
					state = .completed(value.map(Self.fetched) ?? .failed(error ?? NoResult()))
					return nil
				}
				return value
			}
			if let late {
				lateValue?(late)
			}
			completed.signal()
		}
		_ = completed.wait(timeout: .now() + timeout)
		self = state.withLock { state in
			guard case let .completed(outcome) = state else {
				state = .abandoned
				return .timedOut
			}
			return outcome
		}
	}
}
