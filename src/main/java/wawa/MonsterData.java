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
 * kind       -> dat       0xbac4c    53 bytes, dat/&lt;NN&gt;.dat LoadGameMonsOne reads ("%02d")
 * kind       -> ani_*     {@link #ANI}, the state table CalcMonsAnim indexes
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
	private static final int DAT_TABLE = 0xbac4c;

	/**
	 * Per kind, the address of its ani_* table: 6-byte entries {u16, u16 baseFrame, u16 steps}
	 * that CalcMonsAnim picks by animation state. The pointer array the engine reads them through
	 * is filled at run time, so the kind order comes from a dump of that array in the running
	 * game; each address is a .dynsym object (ani_boar, ani_octo, ...), and {@link #ANI_ENTRIES}
	 * is that symbol's size / 6.
	 */
	private static final int[] ANI = {
			0xc18b8, 0xc4830, 0xc4970, 0xc50e0, 0xc2030, 0xc509c, 0xc193c, 0xc1cf4, 0xc35bc, 0xc1d6c,
			0xc1810, 0xc19c8, 0xc1be0, 0xc1a7c, 0xc1fd0, 0xc5024, 0xc20e4, 0xc20fc, 0xc48ec, 0xc1dfc,
			0xc198c, 0xc1a10, 0xc1a40, 0xc4cc0, 0xc4bd0, 0xc4b08, 0xc1c1c, 0xc4b44, 0xc18f4, 0xc18f4,
			0xc4714, 0xc3e8c, 0xc3e8c, 0xc3e8c, 0xc3e8c, 0xc3e8c, 0xc3e8c, 0xc3f1c, 0xc36ac, 0xc36ac,
			0xc36ac, 0xc36ac, 0xc36ac, 0xc36ac, 0xc36ac, 0xc36ac, 0xc36ac, 0xc36ac, 0xc36ac, 0xc36ac,
			0xc36ac, 0xc36ac, 0xc4714,
	};
	private static final int[] ANI_ENTRIES = {
			10, 31, 68, 22, 30, 11, 13, 20, 40, 24, 28, 12, 10, 10, 16, 20, 4, 12, 22, 6,
			10, 8, 10, 60, 40, 10, 36, 23, 12, 12, 47, 4, 4, 4, 4, 4, 4, 4, 48, 48,
			48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 47,
	};

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

	/** The dat/&lt;NN&gt;.dat number holding a kind's animation. */
	public int dat(int kind) {
		return elf.u8(DAT_TABLE + kind % SPRITE_KINDS);
	}

	/** {u16, baseFrame, steps} of one animation state, or null past the end of the kind's table. */
	public int[] animEntry(int kind, int state) {
		int k = kind % SPRITE_KINDS;
		if (state < 0 || state >= ANI_ENTRIES[k]) {
			return null;
		}
		int at = ANI[k] + state * 6;
		return new int[] {elf.u16(at), elf.u16(at + 2), elf.u16(at + 4)};
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
