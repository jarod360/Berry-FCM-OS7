package top.zesun.berryfcm;

interface PrivilegeShell extends AutoCloseable {
    String execute(String command) throws Exception;
    void close();
}
