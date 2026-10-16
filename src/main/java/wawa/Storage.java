package wawa;

/** Save slots: a file per key on the desktop, localStorage on the web. */
interface Storage {

	/** The stored value, or null when there is none. */
	String load(String key);

	void save(String key, String value);

	void delete(String key);
}
