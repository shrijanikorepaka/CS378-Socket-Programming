import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

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
    private final int oraclePort;

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
        int port = ORACLE_PORT;
        int colon = oracleAddr.lastIndexOf(':');

        if(colon > 0) {
            host = oracleAddr.substring(0, colon);
            port = Integer.parseInt(oracleAddr.substring(colon + 1));
        }
        this.oracleIP = host;
        this.oraclePort = port;
        this.ownIP = ownIP;
        this.udpPort = udpPort;
    }

    private void initializeUDP() throws IOException {
        InetAddress localAddress = InetAddress.getByName(ownIP);

        if (localAddress.getAddress().length != 4 || localAddress.isLoopbackAddress()){
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

    private void connectToOracle() throws IOException {
        tcpSocket = new Socket();
        tcpSocket.connect(
                new InetSocketAddress(oracleIP, ORACLE_PORT));

        System.out.println("Connected to Oracle Node at "
                + oracleIP + ":" + ORACLE_PORT);
    }

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

    public static void main(String[] args) {
        if (args.length != 3) {
            System.err.println(
                    "Usage: java VN <oracle-ip> <own-ip> <udp-port>");
            System.exit(1);
        }

        try {
            int port = Integer.parseInt(args[2]);

            vn vn = new vn(args[0], args[1], port);
            vn.start();
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("VN error: " + e.getMessage());
        }
    }
}