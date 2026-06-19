package inspection.common;

import java.io.*;
import java.util.*;

/**
 * 一键启动所有模块（修复版）
 *
 * 启动流程:
 *   1. 自动编译: mvn compile -q
 *   2. 检查 Redis + RabbitMQ 端口
 *   3. 按顺序启动: TaskConfigurator → Navigator → Car×N → Controller → Display
 *   4. 自动打开浏览器 http://localhost:8888
 *   5. Ctrl+C 一键停止所有模块
 *
 * 运行方式: IDEA 中右键 → Run 'Launcher.main()' 或命令行 java Launcher
 */
public class Launcher {

    private static final String JAVA = detectJava();
    private static final File PROJECT_ROOT = detectProjectRoot();
    private static final String M2 = System.getProperty("user.home") + "\\.m2\\repository";
    private static final List<Process> processes = new ArrayList<>();
    private static final int MAX_CARS = 4;  // 默认最大小车进程数

    private static String detectJava() {
        String jh = System.getProperty("java.home");
        if (jh != null) {
            File javaExe = new File(jh, "bin\\java.exe");
            if (javaExe.exists()) return javaExe.getAbsolutePath();
            javaExe = new File(jh, "bin\\java");  // Linux/Mac
            if (javaExe.exists()) return javaExe.getAbsolutePath();
        }
        return "java";
    }

    private static File detectProjectRoot() {
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
        return new File(System.getProperty("user.dir"));
    }

    public static void main(String[] args) throws Exception {
        System.out.println("========================================");
        System.out.println("  BlackBoxAI 一键启动器 v2");
        System.out.println("  项目路径: " + PROJECT_ROOT.getAbsolutePath());
        System.out.println("========================================");

        // Step 1: 自动编译
        long t0 = System.currentTimeMillis();
        if (!autoCompile()) {
            System.out.println("❌ 编译失败，请手动修复后重试");
            System.exit(1);
        }
        System.out.println("✅ 编译完成 (" + (System.currentTimeMillis() - t0) / 1000.0 + "s)");

        // Step 2: 检查基础设施
        checkRedisRabbitMQ();

        // Step 3: 按顺序启动所有模块进程
        String commonClasses = new File(PROJECT_ROOT, "common\\target\\classes").getAbsolutePath();

        launch("TaskConfigurator", "task-configurator",
                "inspection.taskconfigurator.TaskConfiguratorMain", commonClasses, 2000);

        launch("Navigator", "navigator",
                "inspection.navigator.NavigatorMain", commonClasses, 2000);

        // 启动多个 Car 进程（Car001 ~ Car004）
        for (int i = 1; i <= MAX_CARS; i++) {
            String carId = String.format("Car%03d", i);
            launchCar(carId, commonClasses, 800);
        }

        launch("Controller", "controller",
                "inspection.controller.ControllerMain", commonClasses, 2000);

        launch("Display", "display",
                "inspection.display.DisplayMain", commonClasses, 3000);  // 增加 Display 启动等待

        // 等待 Display HTTP 服务器就绪
        Thread.sleep(1000);

        long elapsed = (System.currentTimeMillis() - t0) / 1000;
        System.out.println("\n========================================");
        System.out.println("  全部模块已启动！(耗时 " + elapsed + "s)");
        System.out.println("  浏览器打开: http://localhost:8888");
        System.out.println("  按 Ctrl+C 停止所有模块");
        System.out.println("========================================\n");

        // 自动打开浏览器
        openBrowser("http://localhost:8888");

        // Ctrl+C 一键停止
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n⏳ 正在关闭所有模块...");
            for (int i = processes.size() - 1; i >= 0; i--) {
                processes.get(i).destroyForcibly();
            }
            System.out.println("✅ 所有模块已关闭");
        }));

        Thread.currentThread().join();
    }

    // ==================== 自动编译 ====================

    private static boolean autoCompile() {
        // 检查所有模块是否已编译（而非仅 common 模块）
        File pomFile = new File(PROJECT_ROOT, "pom.xml");
        String[] modules = {"common", "controller", "car", "navigator", "task-configurator", "display"};
        boolean allCompiled = true;
        for (String m : modules) {
            File classesDir = new File(PROJECT_ROOT, m + "\\target\\classes");
            if (!classesDir.exists() || classesDir.lastModified() < pomFile.lastModified()) {
                allCompiled = false;
                break;
            }
        }
        if (allCompiled) {
            System.out.println("📦 所有模块已有编译产物，跳过编译");
            return true;
        }

        System.out.println("📦 正在编译项目 (mvn compile -q)...");
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "mvn", "compile", "-q", "-Dorg.slf4j.simpleLogger.log.org.apache.maven.plugins=error"
            );
            pb.directory(PROJECT_ROOT);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            // 读取输出（避免管道阻塞）
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int exitCode = p.waitFor();
            if (exitCode != 0) {
                System.out.println("编译失败，输出:");
                System.out.println(output);
                return false;
            }
            return true;
        } catch (IOException e) {
            System.out.println("⚠ 无法运行 mvn，请确认 Maven 已安装且在 PATH 中");
            System.out.println("  尝试跳过编译... (如已用 IDEA Build，可忽略)");
            // 不阻止启动，可能用户已经在 IDEA 中 Build 了
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ==================== 进程启动 ====================

    private static void launch(String name, String module, String mainClass,
                                String commonClassesDir, long delayMs) throws Exception {
        String classesDir = new File(PROJECT_ROOT, module + "\\target\\classes").getAbsolutePath();
        String cp = buildClasspath(commonClassesDir, classesDir, module);

        Process p = startProcess(name, mainClass, cp);
        waitAndReport(name, p, delayMs);
    }

    private static void launchCar(String carId, String commonClassesDir, long delayMs) throws Exception {
        String classesDir = new File(PROJECT_ROOT, "car\\target\\classes").getAbsolutePath();
        String cp = buildClasspath(commonClassesDir, classesDir, "car");

        ProcessBuilder pb = new ProcessBuilder(JAVA,
                "-Dfile.encoding=UTF-8",           // 统一 JVM 默认编码
                "-Dsun.stdout.encoding=UTF-8",     // 强制 stdout 使用 UTF-8（Windows 下避免 GBK 乱码）
                "-Dsun.stderr.encoding=UTF-8",     // 强制 stderr 使用 UTF-8
                "-cp", cp,
                "inspection.car.CarMain", carId);
        pb.directory(PROJECT_ROOT);
        pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
        pb.redirectError(ProcessBuilder.Redirect.PIPE);

        Process p = pb.start();
        processes.add(p);
        startLogReaders("Car:" + carId, p);
        waitAndReport("Car:" + carId, p, delayMs);
    }

    private static Process startProcess(String name, String mainClass, String cp) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(JAVA,
                "-Dfile.encoding=UTF-8",           // 统一 JVM 默认编码
                "-Dsun.stdout.encoding=UTF-8",     // 强制 stdout 使用 UTF-8（Windows 下避免 GBK 乱码）
                "-Dsun.stderr.encoding=UTF-8",     // 强制 stderr 使用 UTF-8
                "-cp", cp, mainClass);
        pb.directory(PROJECT_ROOT);
        pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
        pb.redirectError(ProcessBuilder.Redirect.PIPE);

        Process p = pb.start();
        processes.add(p);
        startLogReaders(name, p);
        return p;
    }

    /**
     * 启动 stdout + stderr 双线程读取，带标签前缀输出。
     * 显式指定 UTF-8 编解码，解决 Windows 系统 GBK/UTF-8 编码不一致导致的乱码。
     */
    private static void startLogReaders(String label, Process p) {
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(),
                            java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("[" + label + "] " + line);
                }
            } catch (IOException ignored) {}
        }, label + "-stdout").start();

        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getErrorStream(),
                            java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.err.println("[" + label + " ERR] " + line);
                }
            } catch (IOException ignored) {}
        }, label + "-stderr").start();
    }

    private static void waitAndReport(String name, Process p, long delayMs) throws Exception {
        System.out.print("  启动 " + name + "... ");
        Thread.sleep(Math.min(delayMs, 1500));  // 等一会儿让进程初始化

        if (p.isAlive()) {
            System.out.println("✅ OK");
        } else {
            int exitCode = p.exitValue();
            System.out.println("❌ 启动失败 (exit=" + exitCode + ")");
            // 检查常见原因
            if (exitCode == 1) {
                System.out.println("   → 可能原因: classpath 缺失 JAR、Redis/RabbitMQ 未就绪");
            }
        }
        Thread.sleep(Math.max(0, delayMs - 1500));
    }

    // ==================== Classpath 构建 ====================

    private static String buildClasspath(String commonDir, String moduleDir, String module) {
        // 优先使用 mvn 生成的 classpath（最准确），失败则回退到扫描模式
        String mvnCp = tryMvnClasspath(module);
        if (mvnCp != null && !mvnCp.isEmpty()) {
            return commonDir + File.pathSeparator + moduleDir + File.pathSeparator + mvnCp;
        }

        // 回退：扫描 .m2 仓库
        return buildClasspathByScan(commonDir, moduleDir);
    }

    /** 使用 mvn dependency:build-classpath 获取精确 classpath */
    private static String tryMvnClasspath(String module) {
        try {
            // 用临时文件接收 classpath（兼容 Windows 无 /dev/stdout）
            File tmpFile = File.createTempFile("mvn-cp-", ".txt");
            tmpFile.deleteOnExit();
            ProcessBuilder pb = new ProcessBuilder(
                    "mvn", "dependency:build-classpath",
                    "-pl", module,
                    "-DincludeScope=runtime",
                    "-Dmdep.outputFile=" + tmpFile.getAbsolutePath(),
                    "-q"
            );
            pb.directory(PROJECT_ROOT);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            p.waitFor();

            if (tmpFile.exists() && tmpFile.length() > 0) {
                String cp = new String(java.nio.file.Files.readAllBytes(tmpFile.toPath())).trim();
                if (!cp.isEmpty()) return cp;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** 回退方案：扫描 .m2 仓库 */
    private static String buildClasspathByScan(String commonDir, String moduleDir) {
        Map<String, String> bestJars = new LinkedHashMap<>();

        String[] m2Dirs = {
            "com\\alibaba\\fastjson2",
            "com\\rabbitmq",
            "redis\\clients",
            "org\\slf4j",
            "org\\apache\\commons\\commons-pool2",
            "org\\java-websocket",          // Display 模块需要
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
        sb.append(commonDir).append(File.pathSeparator).append(moduleDir);
        for (String jar : bestJars.values()) {
            sb.append(File.pathSeparator).append(jar);
        }
        return sb.toString();
    }

    /** 递归扫描只保留每个 artifact 的最高版本 */
    private static void collectBestJars(File dir, Map<String, String> bestJars) {
        File[] files = dir.listFiles();
        if (files == null) return;

        boolean hasVersionDirs = false;
        for (File f : files) {
            if (f.isDirectory() && f.getName().matches("[0-9].*")) {
                hasVersionDirs = true;
                break;
            }
        }

        if (hasVersionDirs) {
            String latestVer = null;
            for (File f : files) {
                if (f.isDirectory() && f.getName().matches("[0-9].*")) {
                    if (latestVer == null || compareVersions(f.getName(), latestVer) > 0) {
                        latestVer = f.getName();
                    }
                }
            }
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
            for (File f : files) {
                if (f.isDirectory()) {
                    collectBestJars(f, bestJars);
                }
            }
        }
    }

    /** 版本号比较 */
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

    // ==================== 基础设施检查 ====================

    private static void checkRedisRabbitMQ() {
        boolean redisOk = checkPort("localhost", 6379);
        boolean rabbitOk = checkPort("localhost", 5672);

        if (!redisOk) {
            System.out.println("⚠ Redis (6379) 未启动！请在另一个终端运行: redis-server");
        }
        if (!rabbitOk) {
            System.out.println("⚠ RabbitMQ (5672) 未启动！请确保 RabbitMQ 服务正在运行");
        }
        if (redisOk && rabbitOk) {
            System.out.println("✅ Redis + RabbitMQ 已就绪");
        } else {
            System.out.println("========================================");
        }
    }

    private static boolean checkPort(String host, int port) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(host, port), 1500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ==================== 浏览器 ====================

    private static void openBrowser(String url) {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                new ProcessBuilder("cmd", "/c", "start", url).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", url).start();
            } else {
                new ProcessBuilder("xdg-open", url).start();
            }
            System.out.println("🌐 浏览器已打开: " + url);
        } catch (IOException e) {
            System.out.println("🌐 请手动打开浏览器: " + url);
        }
    }
}