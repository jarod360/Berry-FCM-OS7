package top.zesun.berryfcm;

public final class AdbConnectPortSmoke {
    public static void main(String[] args) {
        if (AdbConnectPort.select(0, 0) != null) throw new AssertionError("NSD must receive null");
        if (AdbConnectPort.select(0, -1) != null) throw new AssertionError("Invalid property");
        if (!Integer.valueOf(43123).equals(AdbConnectPort.select(0, 43123))) throw new AssertionError("System TLS port");
        if (!Integer.valueOf(34567).equals(AdbConnectPort.select(34567, 43123))) throw new AssertionError("Explicit port priority");
        System.out.println("PASS: nullable ADB TLS connect port does not unbox null");
    }
}
