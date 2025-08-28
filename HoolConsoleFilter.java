package pixik.ru.hoolconsolefilter;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.*;

public final class HoolConsoleFilter extends JavaPlugin {

    private final Map<String, MessageData> messageCache = new ConcurrentHashMap<>();
    private final Set<String> ignoredMessages = ConcurrentHashMap.newKeySet();
    private BufferedWriter logWriter;
    private Path logFile;
    private final long CLEANUP_INTERVAL = 60000L;
    private final long MESSAGE_TIMEOUT = 300000L;

    @Override
    public void onEnable() {
        try {
            Path pluginFolder = getDataFolder().toPath();
            if (!Files.exists(pluginFolder)) {
                Files.createDirectories(pluginFolder);
            }

            String fileName = "console_log_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".txt";
            logFile = pluginFolder.resolve(fileName);
            logWriter = new BufferedWriter(new FileWriter(logFile.toFile(), true));

            getLogger().info("Лог-файл создан: " + logFile);

        } catch (IOException e) {
            getLogger().severe("Ошибка при создании лог-файла: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        interceptLogger();

        new CleanupTask().runTaskTimer(this, CLEANUP_INTERVAL, CLEANUP_INTERVAL);

    }

    @Override
    public void onDisable() {
        try {
            if (logWriter != null) {
                logWriter.close();
            }
        } catch (IOException e) {
            getLogger().warning("Ошибка при закрытии лог-файла: " + e.getMessage());
        }

    }

    private void interceptLogger() {
        Logger rootLogger = Logger.getLogger("");

        Handler[] originalHandlers = rootLogger.getHandlers();

        for (Handler handler : originalHandlers) {
            rootLogger.removeHandler(handler);
        }

        rootLogger.addHandler(new CustomHandler(originalHandlers));
    }

    private class CustomHandler extends Handler {
        private final Handler[] originalHandlers;

        public CustomHandler(Handler[] originalHandlers) {
            this.originalHandlers = originalHandlers;
        }

        @Override
        public void publish(LogRecord record) {
            String message = record.getMessage();

            if (message == null || message.trim().isEmpty() ||
                    message.contains(" issued server command:") ||
                    message.startsWith("<") || message.contains(">")) {
                return;
            }

            if (ignoredMessages.contains(message)) {
                return;
            }

            long currentTime = System.currentTimeMillis();
            MessageData cached = messageCache.get(message);

            if (cached != null) {
                cached.count++;
                cached.lastSeen = currentTime;

                if (cached.count > 1) {
                    writeToLog("[" + cached.count + "x] " + message);
                }
            } else {
                messageCache.put(message, new MessageData(currentTime, 1));

                for (Handler handler : originalHandlers) {
                    handler.publish(record);
                }
            }
        }

        @Override
        public void flush() {
            for (Handler handler : originalHandlers) {
                handler.flush();
            }
        }

        @Override
        public void close() throws SecurityException {
            for (Handler handler : originalHandlers) {
                handler.close();
            }
        }
    }

    private void writeToLog(String message) {
        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            logWriter.write("[" + timestamp + "] " + message);
            logWriter.newLine();
            logWriter.flush();
        } catch (IOException e) {
            getLogger().warning("Ошибка при записи в лог-файл: " + e.getMessage());
        }
    }

    private class CleanupTask extends BukkitRunnable {
        @Override
        public void run() {
            long currentTime = System.currentTimeMillis();
            Iterator<Map.Entry<String, MessageData>> iterator = messageCache.entrySet().iterator();

            while (iterator.hasNext()) {
                Map.Entry<String, MessageData> entry = iterator.next();
                MessageData data = entry.getValue();

                if (currentTime - data.lastSeen > MESSAGE_TIMEOUT) {
                    if (data.count > 1) {
                        writeToLog("[ИТОГ] Сообщение '" + entry.getKey() + "' повторялось " + data.count + " раз");
                    }
                    iterator.remove();
                }
            }
        }
    }

    private static class MessageData {
        long lastSeen;
        int count;

        MessageData(long lastSeen, int count) {
            this.lastSeen = lastSeen;
            this.count = count;
        }
    }
}