package wawa;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;

/**
 * Per-monster data read out of lib/armeabi/libwawa.so, where the native build keeps it as
 * read-only tables.
 *
 * <pre>
 * monster id -> kind      0xbe514   211 bytes, what InitMonsOne looks up (g_anMonsLook_K)
 * kind       -> sprite    0xbabdc    53 bytes, the archive LoadGameMonsOne loads
 * monster id -> palette   0xbe7e8   211 bytes, the variant LoadSpritePal applies
 * monster id -> name      0xe186c   211 pointers to NUL-terminated CP949 strings
 * </pre>
 *
 * LoadGameMonsOne names the archive with {@code sprintf("%03d", sprite)}, which is why the
 * sprites are img/&lt;NNN&gt;.png inside this APK.
 */
public final class MonsterData {

	private static final int KIND_TABLE = 0xbe514;
	private static final int SPRITE_TABLE = 0xbabdc;
	private static final int PALETTE_TABLE = 0xbe7e8;
	private static final int NAME_TABLE = 0xe186c;
	private static final int SPRITE_KINDS = 53;

	/** The name table runs to 0xd2; InitMonsOne guards its stat path on ids below 0xa6. */
	public static final int COUNT = 211;

	private static final Charset KOREAN = Charset.forName("MS949");

	private final ElfImage elf;

	private MonsterData(ElfImage elf) {
		this.elf = elf;
	}

	public static MonsterData load(Path so) throws IOException {
		return new MonsterData(ElfImage.read(so));
	}

	/** The kind a monster id belongs to; several ids share one kind. */
	public int kind(int monsterId) {
		return elf.u8(KIND_TABLE + clamp(monsterId));
	}

	/** The sprite archive number, i.e. img/&lt;NNN&gt;.png, for a monster id. */
	public int sprite(int monsterId) {
		return elf.u8(SPRITE_TABLE + kind(monsterId) % SPRITE_KINDS);
	}

	/** The palette variant LoadSpritePal would apply. */
	public int palette(int monsterId) {
		return elf.u8(PALETTE_TABLE + clamp(monsterId));
	}

	/** The shipped name, e.g. "뿔어금니 보어", or null when the entry is empty. */
	public String name(int monsterId) {
		return elf.cstring(elf.u32(NAME_TABLE + clamp(monsterId) * 4), KOREAN);
	}

	public String spriteName(int monsterId) {
		return String.format("%03d.png", sprite(monsterId));
	}

	private static int clamp(int monsterId) {
		return monsterId < 0 ? 0 : Math.min(monsterId, COUNT - 1);
	}
}
