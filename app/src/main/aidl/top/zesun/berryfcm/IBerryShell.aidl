package top.zesun.berryfcm;

// Binder is provided only by an authorized Shizuku UserService connection.
interface IBerryShell {
    void destroy() = 16777114;
    String execute(String command) = 1;
}
