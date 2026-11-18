package top.zesun.berryfcm;

public final class PairingGateSmoke {
    private static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) {
        PairingGate gate = new PairingGate();
        int a = gate.begin();
        check(!gate.submit(a, 12345));
        check(gate.found(a, 12345));
        check(!gate.submit(a, 23456));
        gate.lost(a, 12345);
        check(!gate.submit(a, 12345));
        check(gate.found(a, 23456));
        check(gate.submit(a, 23456));
        check(!gate.submit(a, 23456));
        check(gate.pairing(a));
        check(!gate.found(a, 33333));
        gate.cancel();
        check(!gate.pairing(a));
        int b = gate.begin();
        check(!gate.found(a,12345));
        check(gate.found(b,23456));
        check(!gate.submit(a,23456));
        check(gate.submit(b,23456));
        System.out.println("PASS: discovery-before-input, port loss, duplicate and stale replies");
    }
}
