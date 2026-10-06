package org.cryptomator.frontend.fskit.protocol;

public enum Opcode {
	HELLO(1),
	STATFS(2),
	LOOKUP(3),
	FORGET(4),
	GETATTR(5),
	SETATTR(6),
	READDIR(7),
	CREATE(8),
	REMOVE(9),
	RENAME(10),
	OPEN(11),
	CLOSE(12),
	READ(13),
	WRITE(14),
	SYNC(15),
	READLINK(16),
	SYMLINK(17);

	private final int wireValue;

	Opcode(int wireValue) {
		this.wireValue = wireValue;
	}

	public int wireValue() {
		return wireValue;
	}

	public static Opcode of(int wireValue) throws ProtocolException {
		for (Opcode opcode : values()) {
			if (opcode.wireValue == wireValue) {
				return opcode;
			}
		}
		throw new ProtocolException("Unknown opcode " + wireValue);
	}
}
