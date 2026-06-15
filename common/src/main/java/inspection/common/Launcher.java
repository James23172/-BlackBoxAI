package inspection.common;

import java.io.*;
import java.util.*;

/**
 * 一键启动所有模块
 * 按顺序启动: TaskConfigurator → Navigator → TargetPlanner → Car → Controller → Display
 * <p>
 * 运行方式: 在 IDEA 中右键 → Run 'Launcher.main()'
 */
public class Launcher {

    // 自动检测 Java 路径（兼容任何电脑）
    private static final String JAVA = detectJava();

    private static String detectJava() {
        String jh = System.getProperty("java.home");
        if (jh != null) {
            File javaExe = new File(jh, "bin\\java.exe");
            if (javaExe.exists()) return javaExe.getAbsolutePath();
        }
        return "java"; // fallback: 依赖 PATH
    }
    // 自动检测项目根目录（从当前 class 路径反推）
    private static final File PROJECT_ROOT = detectProjectRoot();

    private static File detectProjectRoot() {
        // 从 common/target/classes/... 往上找 pom.xml
        try {
            String clsPath = Launcher.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI().getPath();
            File dir = new File(clsPath);
            while (dir != null) {
                if (new File(dir, "pom.xml").exists() 
                        && new File(dir, "common").isDirectory()) {
                    return dir;
                }
                dir = dir.getParentFile();
            }
        } catch (Exception ignored) {}
        // fallback: 当前工作目录
        return new File(System.getProperty("user.dir"));
    }

    // Maven 本地仓库
    private static final String M2 = System.getProperty("user.home") + "\\.m2\\repository";

    private static final List<Process> processes = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        System.out.println("========================================");
        System.out.println("  BlackBoxAI 一键启动器");
        System.out.println("========================================");

        // 确保 Redis 和 RabbitMQ 开着
        checkRedisRabbitMQ();

        // 按顺序启动
        launch("TaskConfigurator", "task-configurator",
                "inspection.taskconfigurator.TaskConfiguratorMain", 2000);
        launch("Navigator", "navigator",
                "inspection.navigator.NavigatorMain", 2000);
        launch("TargetPlanner", "target-planner",
                "inspection.targetplanner.TargetPlannerMain", 2000);
        launch("Car", "car",
                "inspection.car.CarMain", 2000);
        launch("Controller", "controller",
                "inspection.controller.ControllerMain", 2000);
        launch("Display", "display",
                "inspection.display.DisplayMain", 1000);

        System.out.println("\n========================================");
        System.out.println("  全部模块已启动！");
        System.out.println("  浏览器打开: http://localhost:8888");
        System.out.println("  按 Ctrl+C 停止所有模块");
        System.out.println("========================================\n");

        // 注册关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n正在关闭所有模块...");
            for (int i = processes.size() - 1; i >= 0; i--) {
                processes.get(i).destroyForcibly();
            }
            System.out.println("所有模块已关闭");
        }));

        // 保持主线程存活
        Thread.currentThread().join();
    }

    private static void launch(String name, String module, String mainClass, long delayMs)
            throws Exception {
        System.out.print("启动 " + name + "... ");

        String classesDir = new File(PROJECT_ROOT, module + "\\target\\classes").getAbsolutePath();
        String commonClasses = new File(PROJECT_ROOT, "common\\target\\classes").getAbsolutePath();

        // 构建 classpath
        String cp = buildClasspath(commonClasses, classesDir);

        ProcessBuilder pb = new ProcessBuilder(
                JAVA,
                "-cp", cp,
                mainClass
        );
        pb.directory(PROJECT_ROOT);
        pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
        pb.redirectError(ProcessBuilder.Redirect.PIPE);

        Process p = pb.start();
        processes.add(p);

        // 启动单独的线程来打印日志
        String label = name;
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("[" + label + "] " + line);
                }
            } catch (IOException ignored) {}
        }).start();

        // 等一秒看有没有报错
        Thread.sleep(1000);
        if (p.isAlive()) {
            System.out.println("✅ OK");
        } else {
            System.out.println("❌ 启动失败");
            try (BufferedReader err = new BufferedReader(
                    new InputStreamReader(p.getErrorStream()))) {
                String line;
                while ((line = err.readLine()) != null) {
                    System.err.println("[" + name + " ERR] " + line);
                }
            }
        }

        Thread.sleep(delayMs);
    }

    /**
     * 自动扫描 .m2 仓库，去重保留最高版本
     */
    private static String buildClasspath(String commonDir, String moduleDir) {
        // key=artifactName(如slf4j-api), value=全路径
        Map<String, String> bestJars = new LinkedHashMap<>();

        String[] m2Dirs = {
            "com\\alibaba\\fastjson2",
            "com\\rabbitmq",
            "redis\\clients",
            "org\\slf4j",
            "org\\apache\\commons\\commons-pool2",
            "org\\java-websocket",
            "org\\json",
            "com\\google\\code\\gson",
            "com\\google\\errorprone",
        };

        for (String dir : m2Dirs) {
            File base = new File(M2, dir);
            if (base.exists()) {
                collectBestJars(base, bestJars);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append(commonDir).append(File.pathSeparator);
        sb.append(moduleDir);
        for (String jar : bestJars.values()) {
            sb.append(File.pathSeparator).append(jar);
        }
        return sb.toString();
    }

    /** 只保留每个 artifact 的最高版本，避免版本冲突 */
    private static void collectBestJars(File dir, Map<String, String> bestJars) {
        File[] files = dir.listFiles();
        if (files == null) return;

        // 判断当前 dir 是否为 artifact 目录(下面有版本号子目录)
        boolean hasVersionDirs = false;
        for (File f : files) {
            if (f.isDirectory() && f.getName().matches("[0-9].*")) {
                hasVersionDirs = true;
                break;
            }
        }

        if (hasVersionDirs) {
            // artifact 目录 → 找最新版本
            String latestVer = null;
            for (File f : files) {
                if (f.isDirectory() && f.getName().matches("[0-9].*")) {
                    if (latestVer == null || compareVersions(f.getName(), latestVer) > 0) {
                        latestVer = f.getName();
                    }
                }
            }
            // 扫描最新版本目录中的所有 jar
            File verDir = new File(dir, latestVer);
            File[] jarFiles = verDir.listFiles();
            if (jarFiles != null) {
                for (File jf : jarFiles) {
                    String name = jf.getName();
                    if (name.endsWith(".jar") && !name.contains("sources") && !name.contains("javadoc")) {
                        bestJars.put(dir.getName(), jf.getAbsolutePath());
                    }
                }
            }
        } else {
            // 非 artifact 目录 → 递归进入子目录
            for (File f : files) {
                if (f.isDirectory()) {
                    collectBestJars(f, bestJars);
                }
            }
        }
    }

    /** 简单版本比较: 2.0.16 > 1.7.36 */
    private static int compareVersions(String a, String b) {
        String[] pa = a.split("[.-]");
        String[] pb = b.split("[.-]");
        int len = Math.min(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            try {
                int va = Integer.parseInt(pa[i]);
                int vb = Integer.parseInt(pb[i]);
                if (va != vb) return va - vb;
            } catch (NumberFormatException e) {
                int cmp = pa[i].compareTo(pb[i]);
                if (cmp != 0) return cmp;
            }
        }
        return pa.length - pb.length;
    }

    private static void checkRedisRabbitMQ() {
        boolean redisOk = checkPort("localhost", 6379);
        boolean rabbitOk = checkPort("localhost", 5672);

        if (!redisOk) {
            System.out.println("⚠ Redis (6379) 未启动，请先运行 redis-server.exe");
        }
        if (!rabbitOk) {
            System.out.println("⚠ RabbitMQ (5672) 未启动，请先运行 rabbitmq-server.bat");
        }
        if (!redisOk || !rabbitOk) {
            System.out.println("========================================");
        }
    }

    private static boolean checkPort(String host, int port) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(host, port), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}