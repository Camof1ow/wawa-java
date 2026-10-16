package wawa;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * EUC-KR (KS X 1001) to and from Unicode through {@link KscTable}, because the browser runtime has
 * no MS949 charset. Bytes below 0x80 are ASCII; a pair of bytes 0xa1..0xfe is one table entry.
 */
final class Ksc {

	private static final int ROW = 94;
	private static Map<Character, Integer> reverse;

	/** Decodes like {@code new String(b, off, len, MS949)} for KS X 1001 text; anything else is U+FFFD. */
	static String decode(byte[] b, int off, int len) {
		StringBuilder sb = new StringBuilder(len);
		int end = off + len;
		for (int i = off; i < end; i++) {
			int c = b[i] & 0xff;
			if (c < 0x80) {
				sb.append((char) c);
			} else if (c >= 0xa1 && c <= 0xfe && i + 1 < end && (b[i + 1] & 0xff) >= 0xa1 && (b[i + 1] & 0xff) <= 0xfe) {
				sb.append(KscTable.T.charAt((c - 0xa1) * ROW + (b[++i] & 0xff) - 0xa1));
			} else {
				sb.append('\uFFFD');
			}
		}
		return sb.toString();
	}

	/** The EUC-KR bytes of a string; characters outside the table become '?'. */
	static byte[] encode(String s) {
		Map<Character, Integer> r = reverse();
		ByteArrayOutputStream out = new ByteArrayOutputStream(s.length() * 2);
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			Integer code = c < 0x80 ? Integer.valueOf(c) : r.get(c);
			if (code == null) {
				out.write('?');
			} else if (code < 0x80) {
				out.write(code);
			} else {
				out.write(code >> 8);
				out.write(code & 0xff);
			}
		}
		return out.toByteArray();
	}

	private static Map<Character, Integer> reverse() {
		if (reverse == null) {
			Map<Character, Integer> r = new HashMap<>(KscTable.T.length() * 2);
			for (int i = 0; i < KscTable.T.length(); i++) {
				char c = KscTable.T.charAt(i);
				if (c != '\uFFFD') {
					r.putIfAbsent(c, (0xa1 + i / ROW) << 8 | 0xa1 + i % ROW);
				}
			}
			reverse = r;
		}
		return reverse;
	}

	private Ksc() {
	}
}
