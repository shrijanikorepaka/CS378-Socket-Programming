import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

// javac vn.java
// java vn <oracle-ip> <own-ip> <udp-port>
// java vn <oracle-ip> <udp-port>

// hostname -I     -> to find ip address

public class vn {
    private static final int ORACLE_PORT = 5000;
    private static final int TUPLE_SIZE = 9;
    private static final int LSP_HEADER_SIZE = 7;
    private static final long LSP_PERIOD_MS = 10_000;
    private static final int LSP_COPIES = 2;

    private static final class Entry {
        final char id;
        final byte[] ip;
        final int port;
        final int cost;

        Entry(char id, byte[] ip, int port, int cost) {
            this.id = id;
            this.ip = ip;
            this.port = port;
            this.cost = cost;
        }

        InetSocketAddress address() throws UnknownHostException {
            return new InetSocketAddress(InetAddress.getByAddress(ip), port);
        }
    }

    private final String oracleIP;
    private final String ownIP;
    private final int udpPort;

    private Socket tcpSocket;
    private DatagramSocket udpSocket;

    private final Object lock = new Object();
    private char myId = 0;
    private int mySeq = 0;
    private List<Entry> myEntries = new ArrayList<>();
    private final Map<Character, InetSocketAddress> addrs = new HashMap<>();
    private final Map<Character, Map<Character, Integer>> graph = new TreeMap<>();
    private final Map<Character, Integer> lastSeq = new HashMap<>();

    public vn(String oracleAddr, String ownIP, int udpPort) {
        String host = oracleAddr;
        int colon = oracleAddr.lastIndexOf(':');

        if(colon > 0) {
            host = oracleAddr.substring(0, colon);
        }
        this.oracleIP = host;
        this.ownIP = ownIP;
        this.udpPort = udpPort;
    }

    // Opens the local UDP socket
    private void initializeUDP() throws IOException {
        InetAddress localAddress = InetAddress.getByName(ownIP);

        if (localAddress.getAddress().length != 4){
            throw new IllegalArgumentException("Use a reachable, non-loopback IPv4 address");
        }

        if (udpPort < 1 || udpPort > 65535) {
            throw new IllegalArgumentException("Invalid UDP port : " + udpPort);
        }

        try {
            udpSocket = new DatagramSocket(new InetSocketAddress(localAddress, udpPort));
        } catch (SocketException e) {
            throw new IOException("Cannot bind UDP socket to " + ownIP + " : " + udpPort
                + " (" + e.getMessage() + ")", e);
        }
        System.out.println("UDP socket bound to " + ownIP + " : " + udpPort);
    }

    // Establishes TCP connection to Oracle
    private void connectToOracle() throws IOException {
        tcpSocket = new Socket();
        tcpSocket.connect(
                new InetSocketAddress(oracleIP, ORACLE_PORT));

        System.out.println("Connected to Oracle Node at "
                + oracleIP + ":" + ORACLE_PORT);
    }

    // Sends node's IPv4 address and UDP port
    private void sendConnect() throws IOException {
        byte[] ip = InetAddress.getByName(ownIP).getAddress();

        if(ip.length != 4 || udpPort < 1 || udpPort > 65535) {
            throw new IllegalArgumentException("Invalid IPv4 address or UDP Port");
        }

        ByteBuffer message = ByteBuffer.allocate(6);
        message.order(ByteOrder.BIG_ENDIAN);
        message.put(ip);
        message.putShort((short) udpPort);

        OutputStream out = tcpSocket.getOutputStream();
        out.write(message.array());
        out.flush();

        System.out.println("Sent 6-byte CONNECT message");
    }

    // LINK-STATE MESSAGE
    // Reads Oracle messages over TCP
    private void oracleLoop() throws IOException {
        DataInputStream in = new DataInputStream(tcpSocket.getInputStream());

        while (true) {
            int len;
            try {
                len = in.readInt();
            } catch (EOFException e) {
                System.out.println("Oracle closed the connection");
                return;
            }
            if(len < 0 || len % TUPLE_SIZE != 0 || len > 65535) {
                throw new IOException("Malformed LINK_STATE MESSAGE (length " + len + ")");
            }
            byte[] payload = new byte[len];
            in.readFully(payload);
            handleLinkState(payload);
        }
    }

    // Decodes binary neighbor entries into Java objects
    private List<Entry> parseEntries(ByteBuffer buf, int count) {
        List<Entry> list = new ArrayList<>();
        for (int i = 0; i < count; i++){
            char id = (char) (buf.get() & 0xFF);
            byte[] ip = new byte[4];
            buf.get(ip);
            int port = buf.getShort() & 0xFFFF;
            int cost = buf.getShort() & 0xFFFF;
            list.add(new Entry(id, ip, port, cost));
        }
        return list;
    }

    // Processes the local node's topology from the Oracle
    private void handleLinkState(byte[] payload) {
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        List<Entry> entries = parseEntries(buf, payload.length / TUPLE_SIZE);

        StringBuilder sb = new StringBuilder();
        for(Entry e : entries) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.id).append('=').append(e.cost);
        }

        System.out.println("LINK_STATE : " + sb);

        synchronized (lock) {
            Map<Character, Integer> links = new TreeMap<>();
            for (Entry e : entries) {
                try {
                    addrs.put(e.id, e.address());
                } catch (UnknownHostException ex) {}

                if (e.cost == 0) {
                    myId = e.id;
                } else {
                    links.put(e.id, e.cost);
                }
            }

            if(myId == 0) {
                System.err.println("LINK-STATE contained no self tuple");
                return;
            }
            myEntries = entries;
            graph.put(myId, links);
        }
        floodOwnLsp();
    }

    // LSP
    // Serializes the local topology into an LSP packet
    private byte[] buildLsp(int seq) {
        ByteBuffer buf = ByteBuffer.allocate(LSP_HEADER_SIZE + TUPLE_SIZE * myEntries.size());
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) myId);
        buf.putInt(seq);
        buf.putShort((short) myEntries.size());
        for (Entry e : myEntries) {
            buf.put((byte) e.id);
            buf.put(e.ip);
            buf.putShort((short) e.port);
            buf.putShort((short) e.cost);
        }
        return buf.array();
    }

    // Sends the local LSP to the node's neighbors
    private void floodOwnLsp() {
        byte[] data;
        List<InetSocketAddress> targets = new ArrayList<>();
        synchronized (lock) {
            if (myId == 0) return;
            mySeq++;
            lastSeq.put(myId, mySeq);
            data = buildLsp(mySeq);

            for (Entry e : myEntries) {
                if (e.cost > 0) {
                    InetSocketAddress a = addrs.get(e.id);
                    if (a != null) targets.add(a);
                }
            }
        }

        for (InetSocketAddress t : targets) {
            sendUdp(data, t);
        }
    }

    // Sends a UDP packet twice
    private void sendUdp (byte[] data, InetSocketAddress target) {
        for (int i = 0; i < LSP_COPIES; i++) {
            try {
                udpSocket.send(new DatagramPacket(data, data.length, target));
            } catch (IOException e) {
                System.err.println("UDP send to " + target + " failed : " + e.getMessage());
            }
        }
    }

    // Listens for incoming UDP packets
    private void udpLoop() {
        byte[] buffer = new byte[4096];
        while (!udpSocket.isClosed()) {
            DatagramPacket pkt = new DatagramPacket(buffer, buffer.length);
            try {
                udpSocket.receive(pkt);
            } catch (IOException e) {
                if (!udpSocket.isClosed()) {
                    System.err.println("UDP recieve error : " + e.getMessage());
                }
                return;
            }
            try {
                handleLSP(Arrays.copyOf(pkt.getData(), pkt.getLength()),
                    new InetSocketAddress(pkt.getAddress(), pkt.getPort()));
            } catch (RuntimeException e) {
                System.err.println("Dropped malformed LSP : " + e.getMessage());
            }
        }
    }

    // Validates, stores, and forwards received LSPs
    private void handleLSP(byte[] data, InetSocketAddress sender) {
        if (data.length < LSP_HEADER_SIZE) return;

        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        char origin = (char) (buf.get() & 0xFF);
        int seq = buf.getInt();
        int count = buf.getShort() & 0xFFFF;
        if (data.length != LSP_HEADER_SIZE + count * TUPLE_SIZE) return;

        List<Entry> entries = parseEntries(buf, count);
        List<InetSocketAddress> targets = new ArrayList<>();

        synchronized (lock) {
            if (origin == myId) return;
            Integer last = lastSeq.get(origin);
            if (last != null && seq <= last) return;
            lastSeq.put(origin, seq);

            Map<Character, Integer> links = new TreeMap<>();
            for (Entry e : entries) {
                try {
                    addrs.put(e.id, e.address());
                } catch (UnknownHostException ex) {}
                if (e.cost > 0) links.put(e.id, e.cost);
            }
            graph.put(origin, links);

            for (Entry e : myEntries) {
                if (e.cost > 0) {
                    InetSocketAddress a = addrs.get(e.id);
                    if (a != null && !a.equals(sender)) targets.add(a);
                }
            }
        }
        for (InetSocketAddress t : targets) {
            sendUdp(data, t);
        }
    }

    // Resends local LSPs and prints the graph periodically
    private void periodicLoop() {
        while (true) {
            try {
                Thread.sleep(LSP_PERIOD_MS);
            } catch (InterruptedException e) {
                return;
            }
            floodOwnLsp();
            printGraph();
        }
    }

    private void printGraph() {
        synchronized (lock) {
            if (graph.isEmpty()) return;

            int max = 'A';
            for (Map.Entry<Character, Map<Character, Integer>> e : graph.entrySet()) {
                max = Math.max(max, e.getKey());
                for (char n : e.getValue().keySet()) max = Math.max(max, n);
            }
            int n = max - 'A' + 1;
            if (n < 2) return;

            StringBuilder sb = new StringBuilder();
            sb.append("--- Known topology at VN ").append(myId).append(" ---\n");
            for (int i = 0; i < n - 1; i++) {
                for (int j = i + 1; j < n; j++) {
                    char a = (char) ('A' + i);
                    char b = (char) ('A' + j);
                    Integer c = null;
                    if (graph.containsKey(a)) c = graph.get(a).get(b);
                    if (c == null && graph.containsKey(b)) c = graph.get(b).get(a);
                    sb.append(c == null ? -1 : c);
                    if (j < n - 1) sb.append('\t');
                }
                sb.append('\n');
            }
            System.out.print(sb);
            System.out.flush();
        }
    }

    // Main
    // Initializes networking and starts the worker threads
    public void start() throws IOException {
        try {
            initializeUDP();
            connectToOracle();
            sendConnect();

            Thread udpThread = new Thread(this::udpLoop, "udp-receiver");
            udpThread.setDaemon(true);
            udpThread.start();

            Thread periodic = new Thread(this::periodicLoop, "periodic");
            periodic.setDaemon(true);
            periodic.start();

            oracleLoop();
        } finally {
            if (tcpSocket != null) tcpSocket.close();
            if (udpSocket != null) udpSocket.close();
        }
    }

    // Detects the local interface IP used to reach the Oracle
    private static String detectOwnIP(String oracleAddr) throws IOException {
        int colon = oracleAddr.lastIndexOf(':');
        String host = colon > 0 ? oracleAddr.substring(0, colon) : oracleAddr;
        try (DatagramSocket s = new DatagramSocket()) {
            s.connect(InetAddress.getByName(host), ORACLE_PORT);
            return s.getLocalAddress().getHostAddress();
        }
    }

    // Parses command-line arguments and starts the node
    public static void main(String[] args) {
        if (args.length != 2 && args.length != 3) {
            System.err.println("Usage: java VN <oracle-ip> <own-ip> <udp-port>");
            System.err.println("   or: java vn <oracle-ip> <udp-port>");
            System.exit(1);
        }

        try {
            String oracleIP = args[0];
            String ownIP;
            int port;
            if (args.length == 3) {
                ownIP = args[1];
                port = Integer.parseInt(args[2]);
            } else {
                port = Integer.parseInt(args[1]);
                ownIP = detectOwnIP(oracleIP);
            }

            new vn(oracleIP, ownIP, port).start();
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("VN error: " + e.getMessage());
            System.exit(1);
        }
    }
}