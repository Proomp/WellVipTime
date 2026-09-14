package com.wellsetups.wellviptime.integration;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Player work follows its entity across Folia regions. Global work must not touch player state. */
public final class ServerTasks implements AutoCloseable {

    @FunctionalInterface
    public interface Task {

        void cancel();
    }

    private record Signature(String name, List<Class<?>> parameters) {}

    private static final ClassValue<Map<Signature, Method>> METHODS =
            new ClassValue<>() {

                @Override
                protected Map<Signature, Method> computeValue(Class<?> type) {
                    return new ConcurrentHashMap<>();
                }
            };

    private final JavaPlugin plugin;

    private final AtomicBoolean closed = new AtomicBoolean();

    private final boolean folia;

    public ServerTasks(JavaPlugin plugin) {
        this.plugin = plugin;
        boolean present;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            present = true;
        } catch (ClassNotFoundException absent) {
            present = false;
        }
        folia = present;
    }

    public boolean folia() {
        return folia;
    }

    private Runnable guarded(Runnable job) {
        return () -> {
            if (!closed.get()) {
                job.run();
            }
        };
    }

    public void execute(Runnable job) {
        schedule(
                () -> {
                    if (!folia) {
                        plugin.getServer().getScheduler().runTask(plugin, guarded(job));
                    } else {
                        invoke(
                                invoke(
                                        plugin.getServer(),
                                        "getGlobalRegionScheduler",
                                        new Class<?>[0]),
                                "execute",
                                new Class<?>[] {Plugin.class, Runnable.class},
                                plugin,
                                guarded(job));
                    }
                });
    }

    public void execute(CommandSender owner, Runnable job) {
        if (!(owner instanceof Player player) || !folia) {
            execute(job);
            return;
        }
        schedule(
                () ->
                        invoke(
                                invoke(player, "getScheduler", new Class<?>[0]),
                                "execute",
                                new Class<?>[] {
                                    Plugin.class, Runnable.class, Runnable.class, long.class
                                },
                                plugin,
                                guarded(job),
                                null,
                                1L));
    }

    private void schedule(Runnable submission) {
        if (closed.get() || !plugin.isEnabled()) {
            return;
        }
        try {
            submission.run();
        } catch (IllegalPluginAccessException failure) {
            // Disable may race an off-thread completion after the initial enabled check.
            if (!closed.get() && plugin.isEnabled()) {
                throw failure;
            }
        }
    }

    /**
     * Traditional servers can close UI synchronously during disable; Folia still requires
     * ownership.
     */
    public void cleanup(Player player, Runnable job) {
        if (!folia && plugin.getServer().isPrimaryThread()) {
            job.run();
        } else {
            execute(player, job);
        }
    }

    public Task repeat(Runnable job, long delay, long period) {
        if (!folia) {
            var task =
                    plugin.getServer()
                            .getScheduler()
                            .runTaskTimer(plugin, guarded(job), delay, period);
            return task::cancel;
        }
        Consumer<Object> consumer = ignored -> guarded(job).run();
        Object task =
                invoke(
                        invoke(plugin.getServer(), "getGlobalRegionScheduler", new Class<?>[0]),
                        "runAtFixedRate",
                        new Class<?>[] {Plugin.class, Consumer.class, long.class, long.class},
                        plugin,
                        consumer,
                        Math.max(1, delay),
                        period);
        return () -> invoke(task, "cancel", new Class<?>[0]);
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) {
        try {
            Signature signature = new Signature(name, List.of(types));
            Map<Signature, Method> methods = METHODS.get(target.getClass());
            Method method = methods.get(signature);
            if (method == null) {
                method = target.getClass().getMethod(name, types);
                if (!method.canAccess(target) && !method.trySetAccessible()) {
                    throw new IllegalAccessException(name);
                }
                methods.put(signature, method);
            }
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Scheduler API failure: " + name, cause);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Scheduler API failure: " + name, failure);
        }
    }

    public boolean closed() {
        return closed.get();
    }

    @Override
    public void close() {
        closed.set(true);
    }
}
