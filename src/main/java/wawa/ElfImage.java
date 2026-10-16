package wawa;

import java.io.IOException;

/**
 * Read-only access to a 32-bit little-endian ELF by virtual address.
 *
 * <p>The native build keeps its tables at fixed virtual addresses, but those are not file offsets:
 * this image has two PT_LOAD segments whose address-to-offset deltas differ by 0x1000, so a single
 * constant cannot translate them. The program headers are parsed instead.
 */
final class ElfImage {

	/** Where the native library sits in the APK. */
	static final String SO = "lib/armeabi/libwawa.so";

	private final byte[] bytes;
	/** {p_vaddr, p_offset, p_filesz} for each PT_LOAD. */
	private final int[][] segments;

	ElfImage(byte[] bytes) throws IOException {
		if (bytes.length < 0x34 || bytes[0] != 0x7f || bytes[1] != 'E' || bytes[2] != 'L'
				|| bytes[3] != 'F') {
			throw new IOException("not an ELF image");
		}
		this.bytes = bytes;
		this.segments = readSegments(bytes);
	}

	static ElfImage read(Assets assets) throws IOException {
		return new ElfImage(assets.read(SO));
	}

	int u8(int vaddr) {
		int at = fileOffset(vaddr);
		return at < 0 ? 0 : bytes[at] & 0xFF;
	}

	int u16(int vaddr) {
		int at = fileOffset(vaddr);
		return at < 0 || at + 2 > bytes.length ? 0 : (bytes[at] & 0xFF) | ((bytes[at + 1] & 0xFF) << 8);
	}

	int s8(int vaddr) {
		return (byte) u8(vaddr);
	}

	int s16(int vaddr) {
		return (short) u16(vaddr);
	}

	int u32(int vaddr) {
		int at = fileOffset(vaddr);
		if (at < 0 || at + 4 > bytes.length) {
			return 0;
		}
		return (bytes[at] & 0xFF) | ((bytes[at + 1] & 0xFF) << 8) | ((bytes[at + 2] & 0xFF) << 16)
				| ((bytes[at + 3] & 0xFF) << 24);
	}

	/** The NUL-terminated EUC-KR string at a virtual address, or null when it is unmapped or empty. */
	String cstring(int vaddr) {
		byte[] b = cbytes(vaddr);
		return b == null || b.length == 0 ? null : Ksc.decode(b, 0, b.length);
	}

	/** The bytes of the NUL-terminated string at a virtual address, or null when it is not mapped. */
	byte[] cbytes(int vaddr) {
		int at = fileOffset(vaddr);
		if (at < 0) {
			return null;
		}
		int end = at;
		while (end < bytes.length && bytes[end] != 0) {
			end++;
		}
		return java.util.Arrays.copyOfRange(bytes, at, end);
	}

	/** {@code n} bytes from a virtual address; bytes past the end of the file read as 0. */
	byte[] bytes(int vaddr, int n) {
		int at = fileOffset(vaddr);
		if (at < 0) {
			throw new IllegalArgumentException("unmapped address 0x" + Integer.toHexString(vaddr));
		}
		return java.util.Arrays.copyOfRange(bytes, at, at + n);
	}

	/** Virtual address to file offset, or -1 when it falls outside every loaded segment. */
	int fileOffset(int vaddr) {
		for (int[] seg : segments) {
			if (vaddr >= seg[0] && vaddr < seg[0] + seg[2]) {
				return seg[1] + (vaddr - seg[0]);
			}
		}
		return -1;
	}

	private static int[][] readSegments(byte[] d) {
		int phoff = u32(d, 0x1c);
		int phentsize = u16(d, 0x2a);
		int phnum = u16(d, 0x2c);
		java.util.List<int[]> out = new java.util.ArrayList<>();
		for (int i = 0; i < phnum; i++) {
			int at = phoff + i * phentsize;
			if (at + 32 > d.length) {
				break;
			}
			if (u32(d, at) != 1) {
				continue;
			}
			out.add(new int[] { u32(d, at + 8), u32(d, at + 4), u32(d, at + 16) });
		}
		return out.toArray(new int[0][]);
	}

	private static int u16(byte[] d, int at) {
		return (d[at] & 0xFF) | ((d[at + 1] & 0xFF) << 8);
	}

	private static int u32(byte[] d, int at) {
		return (d[at] & 0xFF) | ((d[at + 1] & 0xFF) << 8) | ((d[at + 2] & 0xFF) << 16)
				| ((d[at + 3] & 0xFF) << 24);
	}
}
