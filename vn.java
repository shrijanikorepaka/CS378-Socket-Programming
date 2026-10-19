import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class vn {
    private static final int ORACLE_PORT = 5000;

    private final String oracleIP;
    private final String ownIP;
    private final int udpPort;

    private Socket tcpSocket;
    private DatagramSocket udpSocket;

    public vn(String oracleIP, String ownIP, int udpPort) {
        this.oracleIP = oracleIP;
        this.ownIP = ownIP;
        this.udpPort = udpPort;
    }

    private void initializeUDP() throws IOException {
        InetAddress localAddress = InetAddress.getByName(ownIP);

        if (localAddress.getAddress().length != 4 || localAddress.isLoopbackAddress() || localAddress.isAnyLocalAddress()){
            throw new IllegalArgumentException("Use a reachable, non-loopback IPv4 address");
        }

        udpSocket = new DatagramSocket(new InetSocketAddress(localAddress, udpPort));

        System.out.println("UDP socket bound to " + ownIP + " : " + udpSocket);
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
    private void sendTestMessage() throws IOException {
        OutputStream out = tcpSocket.getOutputStream();
        out.write("HELLO FROM VN\n".getBytes());
        out.flush();
        System.out.println("Sent test message to ON");
    }
    public void start() throws IOException {
        try {
            initializeUDP();
            connectToOracle();
            sendConnect();

            System.out.println(
                    "CONNECT sent. Waiting for the ON implementation "
                    + "to support LINK-STATE responses.");
            // Your current on.cpp never sends a response.
            // Implement the receive/decode loop after adding
            // LINK-STATE sending to the ON.
            sendTestMessage(); // temporary connection test

            System.out.println("Test message sent.");
        } finally {
            if (tcpSocket != null) {
                tcpSocket.close();
            }
            if (udpSocket != null) {
                udpSocket.close();
            }
        }
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