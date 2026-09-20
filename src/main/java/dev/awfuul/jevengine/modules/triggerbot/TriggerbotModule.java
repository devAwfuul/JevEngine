package dev.awfuul.jevengine.modules.triggerbot;

import dev.awfuul.jevengine.api.Answer;
import dev.awfuul.jevengine.api.JevResponse;
import dev.awfuul.jevengine.core.JevModule;
import dev.awfuul.jevengine.core.ModuleContext;
import dev.awfuul.jevengine.ui.Branding;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/** Captures a bounded combat sample and asks Jev for a staff-facing assessment. */
public final class TriggerbotModule implements JevModule, Listener {

    public static final String ID = "triggerbot";
    private static final String FILE = "triggerbot.yml";
    private static final String CHECK_PERMISSION = "jevengine.triggerbot.check";

    private ModuleContext context;
    private TriggerbotConfig config;
    private BukkitTask sampler;
    private final Map<UUID, CheckSession> active = new LinkedHashMap<>();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Triggerbot";
    }

    @Override
    public String description() {
        return "Samples five seconds of combat movement and asks Jev about automation.";
    }

    @Override
    public Set<String> commandAliases() {
        return Set.of();
    }

    @Override
    public void enable(ModuleContext moduleContext) {
        context = moduleContext;
        config = TriggerbotConfig.load(moduleContext.config(FILE));
        warn(config);

        Bukkit.getPluginManager().registerEvents(this, moduleContext.plugin());
        startSampler();
    }

    @Override
    public void disable() {
        if (sampler != null) {
            sampler.cancel();
            sampler = null;
        }
        HandlerList.unregisterAll(this);
        active.clear();
    }

    @Override
    public List<String> reload() {
        TriggerbotConfig replacement = TriggerbotConfig.load(context.config(FILE));
        config = replacement;
        warn(replacement);
        startSampler();
        return replacement.warnings();
    }

    private void warn(TriggerbotConfig loaded) {
        for (String warning : loaded.warnings()) {
            context.logger().warning("[" + ID + "] " + warning);
        }
    }

    private void startSampler() {
        if (sampler != null) {
            sampler.cancel();
        }
        int interval = config.sampling().intervalTicks();
        sampler = Bukkit.getScheduler().runTaskTimer(context.plugin(), this::sampleChecks,
                1L, interval);
    }

    private void sampleChecks() {
        if (active.isEmpty()) {
            return;
        }
        List<CheckSession> finished = new ArrayList<>();
        long now = System.nanoTime();
        for (CheckSession session : List.copyOf(active.values())) {
            Player player = Bukkit.getPlayer(session.playerId);
            if (player == null || !player.isOnline()) {
                active.remove(session.playerId);
                session.sender.sendMessage(context.branding().say(
                        "The sample ended because " + session.playerName + " left the server."));
                continue;
            }

            session.addFrame(frame(player, session.startedAtNanos), config.sampling().maxFrames());
            if (now - session.startedAtNanos >= config.sampling().durationSeconds() * 1_000_000_000L) {
                finished.add(session);
            }
        }
        for (CheckSession session : finished) {
            finish(session);
        }
    }

    private Frame frame(Player player, long startedAtNanos) {
        long elapsedMs = Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
        PlayerState subject = state(player);
        double radiusSquared = config.sampling().opponentRadius()
                * config.sampling().opponentRadius();
        List<Player> nearby = player.getWorld().getPlayers().stream()
                .filter(other -> !other.getUniqueId().equals(player.getUniqueId()))
                .filter(other -> other.getLocation().distanceSquared(player.getLocation()) <= radiusSquared)
                .sorted(Comparator.comparingDouble(other ->
                        other.getLocation().distanceSquared(player.getLocation())))
                .limit(config.sampling().maxOpponents())
                .toList();
        List<PlayerState> opponents = nearby.stream().map(this::state).toList();
        return new Frame(elapsedMs, subject, opponents);
    }

    private PlayerState state(Player player) {
        Location location = player.getLocation();
        Vector velocity = player.getVelocity();
        return new PlayerState(
                player.getName(),
                player.getUniqueId().toString(),
                location.getWorld() == null ? "" : location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch(),
                velocity.getX(), velocity.getY(), velocity.getZ(),
                player.getHealth(), player.getMaxHealth(), Math.max(0, player.getPing()),
                player.isOnGround(), player.isSprinting());
    }

    @EventHandler
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)
                || !(event.getEntity() instanceof Player target)) {
            return;
        }
        CheckSession session = active.get(attacker.getUniqueId());
        if (session == null || session.attacks.size() >= config.sampling().maxAttacks()) {
            return;
        }
        double distance = attacker.getLocation().distance(target.getLocation());
        double angle = viewAngle(attacker, target);
        long elapsedMs = Math.max(0L, (System.nanoTime() - session.startedAtNanos) / 1_000_000L);
        session.attacks.add(new Attack(elapsedMs, state(target), event.getFinalDamage(),
                distance, angle, Math.max(0, attacker.getPing())));
    }

    private static double viewAngle(Player attacker, Player target) {
        Vector view = attacker.getEyeLocation().getDirection().normalize();
        Vector toward = target.getEyeLocation().toVector()
                .subtract(attacker.getEyeLocation().toVector());
        if (toward.lengthSquared() == 0.0D) {
            return 180.0D;
        }
        toward.normalize();
        double dot = Math.max(-1.0D, Math.min(1.0D, view.dot(toward)));
        return Math.toDegrees(Math.acos(dot));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        CheckSession session = active.remove(event.getPlayer().getUniqueId());
        if (session != null) {
            session.sender.sendMessage(context.branding().say(
                    "The sample ended because " + session.playerName + " left the server."));
        }
    }

    @Override
    public boolean handleCommand(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission("jevengine.admin") && !sender.hasPermission(CHECK_PERMISSION)) {
            sender.sendMessage(context.branding().say("You do not have permission to do that."));
            return true;
        }
        if (args.length == 0 || !args[0].equalsIgnoreCase("check")) {
            usage(sender, label);
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(context.branding().say("Give me a player to check."));
            return true;
        }
        Player player = Bukkit.getPlayerExact(args[1]);
        if (player == null) {
            player = Bukkit.getPlayer(args[1]);
        }
        if (player == null || !player.isOnline()) {
            sender.sendMessage(context.branding().say("That player is not online."));
            return true;
        }
        if (active.containsKey(player.getUniqueId())) {
            sender.sendMessage(context.branding().say(
                    "A triggerbot sample for " + player.getName() + " is already running."));
            return true;
        }
        if (active.size() >= config.sampling().maxConcurrentChecks()) {
            sender.sendMessage(context.branding().say(
                    "The triggerbot sampler is busy. Try again in a moment."));
            return true;
        }

        CheckSession session = new CheckSession(player, sender);
        active.put(player.getUniqueId(), session);
        sender.sendMessage(context.branding().say("Sampling " + player.getName() + " for "
                + config.sampling().durationSeconds() + " seconds. No action will be taken automatically."));
        return true;
    }

    private void usage(CommandSender sender, String label) {
        sender.sendMessage(context.branding().say("Triggerbot"));
        sender.sendMessage(hint("/" + label + " check <player>",
                "sample combat data and ask Jev for an assessment"));
    }

    private static Component hint(String command, String description) {
        return Component.text()
                .append(Component.text("  " + command + " ", NamedTextColor.WHITE))
                .append(Component.text(description, Branding.MUTED))
                .build();
    }

    private void finish(CheckSession session) {
        if (!active.remove(session.playerId, session)) {
            return;
        }
        Map<String, Object> state = buildState(session);
        if (session.frames.size() < config.sampling().minimumFrames()) {
            session.sender.sendMessage(context.branding().say(
                    "The sample was too short to assess reliably."));
            return;
        }

        session.sender.sendMessage(context.branding().say("Sample complete. Asking Jev..."));
        Map<String, dev.awfuul.jevengine.core.QuestionSpec> questions =
                Map.of(config.questionId(), config.question());
        Plugin plugin = context.plugin();
        context.jev().ask(state, questions).whenComplete((response, failure) -> {
            if (!plugin.isEnabled()) {
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () ->
                    report(session, response, failure));
        });
    }

    private void report(CheckSession session, JevResponse response, Throwable failure) {
        if (failure != null) {
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                    ? failure.getCause() : failure;
            session.sender.sendMessage(context.branding().say(
                    "Jev could not assess " + session.playerName + ": " + cause.getMessage()));
            return;
        }
        Answer.Score score = response.score(config.questionId());
        if (score == null) {
            session.sender.sendMessage(context.branding().say(
                    "Jev returned no triggerbot score for " + session.playerName + "."));
            return;
        }
        boolean concerning = score.score() >= config.reportThreshold();
        Component line = Component.text()
                .append(Component.text("Triggerbot assessment for " + session.playerName + ": ",
                        NamedTextColor.WHITE))
                .append(Component.text(Math.round(score.score()) + "/100",
                        concerning ? NamedTextColor.RED : NamedTextColor.GREEN))
                .append(Component.text(" (" + Math.round(score.confidence() * 100.0D)
                        + "% confidence)", Branding.MUTED))
                .build();
        session.sender.sendMessage(context.branding().prefix().append(line));
        if (!score.nearestLevel().isBlank()) {
            session.sender.sendMessage(Component.text("  " + score.nearestLevel(), Branding.MUTED));
        }
        session.sender.sendMessage(Component.text(
                "  This is an investigation lead, not an automatic punishment.", Branding.MUTED));
    }

    private Map<String, Object> buildState(CheckSession session) {
        List<Map<String, Object>> frames = session.frames.stream().map(this::frameMap).toList();
        List<Map<String, Object>> attacks = session.attacks.stream().map(this::attackMap).toList();

        List<Integer> pings = session.frames.stream().map(frame -> frame.subject.pingMs).toList();
        double averagePing = pings.stream().mapToInt(Integer::intValue).average().orElse(0.0D);
        int minPing = pings.stream().mapToInt(Integer::intValue).min().orElse(0);
        int maxPing = pings.stream().mapToInt(Integer::intValue).max().orElse(0);

        List<Double> yawChanges = new ArrayList<>();
        List<Double> pitchChanges = new ArrayList<>();
        for (int i = 1; i < session.frames.size(); i++) {
            PlayerState before = session.frames.get(i - 1).subject;
            PlayerState after = session.frames.get(i).subject;
            yawChanges.add(Math.abs(wrapDegrees(after.yaw - before.yaw)));
            pitchChanges.add((double) Math.abs(after.pitch - before.pitch));
        }
        List<Long> attackIntervals = new ArrayList<>();
        int targetSwitches = 0;
        for (int i = 1; i < session.attacks.size(); i++) {
            Attack previous = session.attacks.get(i - 1);
            Attack current = session.attacks.get(i);
            attackIntervals.add(current.elapsedMs - previous.elapsedMs);
            if (!current.target.uuid.equals(previous.target.uuid)) {
                targetSwitches++;
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("duration_ms", session.frames.isEmpty() ? 0
                : session.frames.get(session.frames.size() - 1).elapsedMs);
        summary.put("frame_count", session.frames.size());
        summary.put("attack_count", session.attacks.size());
        summary.put("attacks_per_second", session.attacks.size()
                / Math.max(0.001D, config.sampling().durationSeconds()));
        summary.put("attack_intervals_ms", attackIntervals);
        summary.put("target_switches", targetSwitches);
        summary.put("average_ping_ms", averagePing);
        summary.put("min_ping_ms", minPing);
        summary.put("max_ping_ms", maxPing);
        summary.put("mean_abs_yaw_change", average(yawChanges));
        summary.put("max_abs_yaw_change", yawChanges.stream().mapToDouble(Double::doubleValue)
                .max().orElse(0.0D));
        summary.put("mean_abs_pitch_change", average(pitchChanges));
        summary.put("max_abs_pitch_change", pitchChanges.stream().mapToDouble(Double::doubleValue)
                .max().orElse(0.0D));

        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("duration_seconds", config.sampling().durationSeconds());
        sample.put("sampling_interval_ticks", config.sampling().intervalTicks());
        sample.put("ping_is_observed_per_frame_and_must_be_accounted_for", true);
        sample.put("compact_schema", "Each frame has t=milliseconds, s=subject state, and "
                + "o=nearby opponents. State fields are p=position, r=rotation, v=velocity, "
                + "h=health, ping=milliseconds, g=on_ground, and n=name when present. "
                + "Attack records use the same compact target state.");
        sample.put("summary", summary);
        sample.put("frames", frames);
        sample.put("attacks", attacks);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("task", "Minecraft triggerbot detection review");
        root.put("subject", session.playerName);
        root.put("sample", sample);
        root.put("decision_policy", "Do not recommend punishment from this sample alone. "
                + "Separate network delay, target movement, legitimate aim assistance from "
                + "repeated automated timing or rotation patterns.");
        return root;
    }

    private Map<String, Object> frameMap(Frame frame) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("t", frame.elapsedMs);
        map.put("s", compactState(frame.subject, false));
        map.put("o", frame.opponents.stream()
                .map(opponent -> compactState(opponent, true)).toList());
        return map;
    }

    private Map<String, Object> attackMap(Attack attack) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("t", attack.elapsedMs);
        map.put("damage", rounded(attack.damage, 2));
        map.put("distance", rounded(attack.distance, 2));
        map.put("angle", rounded(attack.viewAngle, 1));
        map.put("ping", attack.attackerPingMs);
        map.put("target", compactState(attack.target, true));
        return map;
    }

    private static Map<String, Object> compactState(PlayerState state, boolean includeName) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (includeName) {
            map.put("n", state.name);
        }
        map.put("p", List.of(rounded(state.x, 3), rounded(state.y, 3), rounded(state.z, 3)));
        map.put("r", List.of(rounded(state.yaw, 2), rounded(state.pitch, 2)));
        map.put("v", List.of(rounded(state.velocityX, 3), rounded(state.velocityY, 3),
                rounded(state.velocityZ, 3)));
        map.put("h", rounded(state.health, 2));
        map.put("ping", state.pingMs);
        map.put("g", state.onGround);
        return map;
    }

    private static double rounded(double value, int decimalPlaces) {
        double scale = Math.pow(10.0D, decimalPlaces);
        return Math.round(value * scale) / scale;
    }

    private static double average(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0D);
    }

    private static double wrapDegrees(double degrees) {
        double wrapped = degrees % 360.0D;
        return wrapped > 180.0D ? wrapped - 360.0D : wrapped < -180.0D ? wrapped + 360.0D : wrapped;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return "check".startsWith(prefix) ? List.of("check") : List.of();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("check")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }
        return List.of();
    }

    @Override
    public void appendStatus(Consumer<Component> out) {
        int frames = active.values().stream().mapToInt(session -> session.frames.size()).sum();
        out.accept(row("checks", active.size() + " active, " + frames + " frames captured"));
        out.accept(row("sample", config.sampling().durationSeconds() + " seconds at every "
                + config.sampling().intervalTicks() + " ticks"));
        out.accept(row("threshold", Math.round(config.reportThreshold()) + "/100 report highlight"));
    }

    private static Component row(String label, String value) {
        return Component.text()
                .append(Component.text("    " + label + " ", Branding.MUTED))
                .append(Component.text(value, NamedTextColor.WHITE))
                .build();
    }

    private record PlayerState(String name, String uuid, String world, double x, double y, double z,
                               float yaw, float pitch, double velocityX, double velocityY,
                               double velocityZ, double health, double maxHealth, int pingMs,
                               boolean onGround, boolean sprinting) {
    }

    private record Frame(long elapsedMs, PlayerState subject, List<PlayerState> opponents) {
    }

    private record Attack(long elapsedMs, PlayerState target, double damage, double distance,
                          double viewAngle, int attackerPingMs) {
    }

    private static final class CheckSession {
        private final UUID playerId;
        private final String playerName;
        private final CommandSender sender;
        private final long startedAtNanos;
        private final List<Frame> frames = new ArrayList<>();
        private final List<Attack> attacks = new ArrayList<>();

        private CheckSession(Player player, CommandSender sender) {
            playerId = player.getUniqueId();
            playerName = player.getName();
            this.sender = sender;
            startedAtNanos = System.nanoTime();
        }

        private void addFrame(Frame frame, int maxFrames) {
            if (frames.size() < maxFrames) {
                frames.add(frame);
            }
        }
    }
}
