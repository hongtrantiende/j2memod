package namod.j2me.ninja;

/**
 * Cấu hình 14 server Ninja School.
 * IP mặc định từ patch_server_ip.py: 160.250.130.241:15555
 */
public class ServerConfig {

    public static class Server {
        public final String name;
        public String host;
        public int port;

        public Server(String name, String host, int port) {
            this.name = name;
            this.host = host;
            this.port = port;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    // 14 server Ninja School Online
    // IP từ patch_server_ip.py DEFAULT, port +0..+13 từ base 15555
    private static final Server[] SERVERS = {
        new Server("Bokken",   "160.250.130.241", 15555),
        new Server("Shuriken", "160.250.130.241", 15556),
        new Server("Tessen",   "160.250.130.241", 15557),
        new Server("Kunai",    "160.250.130.241", 15558),
        new Server("Katana",   "160.250.130.241", 15559),
        new Server("Tone",     "160.250.130.241", 15560),
        new Server("Sanzu",    "160.250.130.241", 15561),
        new Server("Sensha",   "160.250.130.241", 15562),
        new Server("Fukiya",   "160.250.130.241", 15563),
        new Server("Tekkan",   "160.250.130.241", 15564),
        new Server("Daisho",   "160.250.130.241", 15565),
        new Server("Bisento",  "160.250.130.241", 15566),
        new Server("Hirosaki", "160.250.130.241", 15567),
        new Server("Haruna",   "160.250.130.241", 15568),
    };

    public static Server[] getAll() { return SERVERS; }

    public static String[] getNames() {
        String[] names = new String[SERVERS.length];
        for (int i = 0; i < SERVERS.length; i++) names[i] = SERVERS[i].name;
        return names;
    }

    public static Server getByName(String name) {
        if (name == null) return SERVERS[0];
        for (Server s : SERVERS) {
            if (s.name.equalsIgnoreCase(name)) return s;
        }
        return SERVERS[0];
    }

    public static Server getByIndex(int index) {
        if (index < 0 || index >= SERVERS.length) return SERVERS[0];
        return SERVERS[index];
    }

    public static int getIndexByName(String name) {
        if (name == null) return 0;
        for (int i = 0; i < SERVERS.length; i++) {
            if (SERVERS[i].name.equalsIgnoreCase(name)) return i;
        }
        return 0;
    }
}
