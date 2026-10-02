package org.cryptomator.frontend.fskit.protocol;

import java.nio.ByteBuffer;

public record Frame(Kind kind, Opcode opcode, long requestId, ByteBuffer control, ByteBuffer payload) {

	public enum Kind {
		REQUEST(0),
		RESPONSE(1);

		private final int wireValue;

		Kind(int wireValue) {
			this.wireValue = wireValue;
		}

		public int wireValue() {
			return wireValue;
		}

		public static Kind of(int wireValue) throws ProtocolException {
			for (Kind kind : values()) {
				if (kind.wireValue == wireValue) {
					return kind;
				}
			}
			throw new ProtocolException("Unknown frame kind " + wireValue);
		}
	}
}
