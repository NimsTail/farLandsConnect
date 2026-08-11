package com.frammy.unitylauncher.signs.features.atm;

import com.frammy.unitylauncher.BlueMapIntegration;
import com.frammy.unitylauncher.UnityCommands;
import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.signs.SignCategory;
import com.frammy.unitylauncher.signs.SignState;
import com.frammy.unitylauncher.signs.SignVariables;
import com.frammy.unitylauncher.signs.render.SignScrollService;
import com.frammy.unitylauncher.signs.storage.SignStore;
import com.frammy.unitylauncher.upgrades.impl.AtmFeesUpgrade;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * ATM signs are fully in-world now — no chest GUI, no chat prompts. Two
 * inputs, reused for everything:
 *
 *  - ЛКМ (Action.LEFT_CLICK_BLOCK): first touch starts a per-player "browse"
 *    session (nothing changes on the real block — see sendSignChange below);
 *    every touch after that confirms whatever option is currently
 *    highlighted and advances to the next step.
 *  - Mouse wheel (PlayerItemHeldEvent, hijacked while a session is active —
 *    see onItemHeld): scrolls the highlighted option. The player's actual
 *    held hotbar slot never changes; we just read the direction and cancel.
 *  - ПКМ (Action.RIGHT_CLICK_BLOCK): step back one level / cancel.
 *
 * Once enough is chosen that only a number (or a number + a name) is left to
 * type, we open the real sign-edit screen (Player#openSign) pre-filled with
 * short prompts and read the answer back from SignChangeEvent — see
 * beginAmountEdit()/onSignEditSubmit(). That screen writes to the REAL sign
 * block (Minecraft has no per-player virtual-text sign editor), so the
 * marquee (SignScrollService) is paused for that sign while it's in use and
 * explicitly restored afterward.
 *
 * The money movement (runActionWithResolvedTarget and below) carried over
 * from the old chest-GUI version as-is at first; the commission model was
 * later reworked (11.08) to "recipient always gets the full amount, sender
 * pays the fee on top" — see atmFeeRate/grossFor near the bottom of this
 * file — to match the site's own /bank/transfer, instead of the old
 * "recipient gets less" model.
 */
public final class AtmController {

    private final UnityLauncher plugin;
    private final BlueMapIntegration blueMap;
    private final SignStore store;
    private final SignScrollService scroll;

    private final Map<UUID, BrowseSession> sessions = new HashMap<>();
    private final Map<UUID, PendingSignEdit> pendingEdits = new HashMap<>();
    private BukkitTask watchdogTask;

    // Raycast range for "is the player still actually looking at this ATM"
    // (getTargetBlockExact) — should comfortably cover normal interact reach.
    private static final int LOOK_RANGE = 6;
    // Fallback-only distance check (see tickWatchdog) in case the raycast
    // check ever misses something (teleport, world change, odd hitbox).
    private static final double MAX_SESSION_DISTANCE_SQ = 8.0 * 8.0;
    // Safety net for the sign-edit screen specifically: Bukkit has no
    // "player closed the sign editor without saving" event at all, so if
    // someone opens the amount prompt and then just disconnects or presses
    // Escape, nothing tells us that happened. Without this the real sign
    // block would be stuck showing "Сумма:" forever.
    private static final long EDIT_TIMEOUT_MS = 45_000L;

    private static final ActionType[] ACTIONS = {
            ActionType.WITHDRAW, ActionType.DEPOSIT, ActionType.TRANSFER_PLAYER, ActionType.TRANSFER_COUNTRY,
            ActionType.BALANCE, ActionType.PREFERENCES, ActionType.INFO
    };
    // Kept short on purpose — sign lines are ~15 narrow-font characters wide
    // and Cyrillic glyphs render noticeably wider than Latin ones, so an
    // exact character budget isn't reliable blind. Tune these in-game if
    // anything clips.
    private static final String[] ACTION_LABELS = { "Снять", "Внести", "-> Игроку", "-> Стране", "Баланс", "Настройки", "Инфо" };

    private final com.frammy.unitylauncher.signs.features.shop.AutoDebitService autoDebit;

    public AtmController(UnityLauncher plugin, BlueMapIntegration blueMap, SignStore store, SignScrollService scroll,
                         com.frammy.unitylauncher.signs.features.shop.AutoDebitService autoDebit) {
        this.plugin = plugin;
        this.blueMap = blueMap;
        this.store = store;
        this.scroll = scroll;
        this.autoDebit = autoDebit;
        startWatchdog();
    }

    // ===== creation (unchanged from the chest-GUI version) =====

    public void onSignCreateATM(SignChangeEvent e,
                                String countryCanonical,
                                int have,
                                int allowed) {

        Player p = e.getPlayer();
        Location loc = SignStore.keyLoc(e.getBlock().getLocation());

        // countryCanonical is Countries.Id (a numeric string, see
        // countryDisplayName's own javadoc below) — never show it directly
        // to a player, resolve to the actual country name first (GH #6).
        String countryName = countryDisplayName(countryCanonical);
        if (countryName == null) countryName = countryCanonical;

        int need = have + 1;
        if (allowed < need) {
            e.setCancelled(true);
            p.sendMessage(ChatColor.RED + "Нельзя поставить ATM №" + need + " для страны [" + countryName + "]. "
                    + ChatColor.GRAY + "Нужно разрешение! (куплено: " + allowed + ").");
            return;
        }

        String title = "ATM [" + countryName + "]";
        e.setLine(0, title);
        e.setLine(1, "Коснитесь,");
        e.setLine(2, "чтобы начать");
        e.setLine(3, "");

        Map<Integer, String> scrollLines = new HashMap<>();
        scrollLines.put(0, title);
        Bukkit.getScheduler().runTask(plugin, () ->
                scroll.makeSignScrollingLines(loc, scrollLines, 6, 13)
        );

        String markerID = "marker_" + UUID.randomUUID();

        store.put(loc, new SignVariables(
                p.getName(),
                countryCanonical,
                List.of(title, "Коснитесь,", "чтобы начать", ""),
                List.of(0),
                false,
                false,
                SignCategory.ATM,
                SignState.ATM_MENU,
                markerID
        ));

        blueMap.addBlueMapMarker(markerID, loc, "services", "Сервисы", "point_atm", null, p);

        p.sendMessage(ChatColor.GREEN + "Банкомат установлен." + ChatColor.GRAY + " (" + need + "/" + allowed + ")");

        UnityCommands.getInstance().getPlayerInfo(p.getName(), data -> {
            if (data == null) return;
            new BukkitRunnable() {
                @Override public void run() {
                    SignVariables sv = store.get(loc);
                    if (sv == null) return;

                    String pretty = data.countryName;
                    if (pretty == null || pretty.isBlank()) return;

                    if (!(loc.getBlock().getState() instanceof Sign sign)) return;

                    String newTitle = "ATM [" + pretty + "]";
                    sign.setLine(0, newTitle);
                    sign.update();

                    List<String> t = new ArrayList<>(sv.getSignText());
                    while (t.size() < 4) t.add("");
                    t.set(0, newTitle);
                    sv.setSignText(t);

                    Map<Integer, String> sl = new HashMap<>();
                    sl.put(0, newTitle);
                    scroll.makeSignScrollingLines(loc, sl, 6, 13);
                }
            }.runTask(UnityLauncher.getInstance());
        });
    }

    // ===== interact (ЛКМ = browse/confirm, ПКМ = back/cancel) =====

    public void onInteract(PlayerInteractEvent e, SignVariables sv) {
        Action act = e.getAction();
        if (act != Action.LEFT_CLICK_BLOCK && act != Action.RIGHT_CLICK_BLOCK) return;

        e.setCancelled(true);
        e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);

        Player p = e.getPlayer();
        Location atmLoc = (e.getClickedBlock() != null) ? SignStore.keyLoc(e.getClickedBlock().getLocation()) : null;
        if (atmLoc == null) return;

        if (act == Action.RIGHT_CLICK_BLOCK) {
            BrowseSession s = sessions.get(p.getUniqueId());
            if (s != null && s.atmLoc.equals(atmLoc)) stepBack(p, s);
            return;
        }

        // ЛКМ
        BrowseSession s = sessions.get(p.getUniqueId());
        if (s == null || !s.atmLoc.equals(atmLoc)) {
            if (s != null) endSession(p, s); // была сессия у другого ATM — закрываем её
            s = new BrowseSession(atmLoc, p.getInventory().getHeldItemSlot());
            sessions.put(p.getUniqueId(), s);
            scroll.pauseScrolling(atmLoc);
            renderBrowse(p, s);
            return;
        }
        confirmCurrentSelection(p, s);
    }

    /** true если событие "съедено" (перехвачен скролл активной ATM-сессии). */
    public boolean onItemHeld(Player p, int previousSlot, int newSlot) {
        BrowseSession s = sessions.get(p.getUniqueId());
        if (s == null) return false;

        // Игнорируем event.getPreviousSlot() как источник истины — если
        // отмена события хоть иногда не успевает откатить слот ДО того, как
        // придёт следующий scroll, previousSlot следующего события будет
        // уже "уехавшим", и косвенно так объяснялись откаты назад при
        // быстром скролле. Держим свой собственный якорь и всегда
        // возвращаем именно к нему — независимо от того, что там у сервера
        // по этому конкретному событию.
        int count = optionsCountFor(s);
        if (count > 1) {
            int dir = scrollDirection(s.anchorSlot, newSlot);
            s.index = Math.floorMod(s.index + dir, count);
            renderBrowse(p, s);
        }

        if (p.getInventory().getHeldItemSlot() != s.anchorSlot) {
            p.getInventory().setHeldItemSlot(s.anchorSlot);
        }
        return true;
    }

    public void onQuit(Player p) {
        if (p == null) return;
        BrowseSession s = sessions.remove(p.getUniqueId());
        if (s != null) scroll.resumeScrolling(s.atmLoc);
        PendingSignEdit pe = pendingEdits.remove(p.getUniqueId());
        if (pe != null) {
            restoreIdleDisplay(pe.atmLoc());
            scroll.resumeScrolling(pe.atmLoc());
        }
    }

    // ===== browse session =====

    private enum BrowseStage { ACTION, WD_KIND, TARGET_MODE, TARGET_LIST, PREFS_MENU }

    private static final class BrowseSession {
        final Location atmLoc;
        // Хотбар-слот, к которому принудительно возвращаем игрока при каждом
        // перехваченном скролле — единственный источник истины для
        // scrollDirection() ниже, event.getPreviousSlot() больше не
        // используем (см. onItemHeld).
        final int anchorSlot;
        BrowseStage stage = BrowseStage.ACTION;
        int index = 0;
        ActionType action;
        TargetKind kind;
        String targetValue;
        List<String> listCache = List.of();
        boolean prefsAutoDebit;
        String prefsPriority = "CASH";

        BrowseSession(Location atmLoc, int anchorSlot) {
            this.atmLoc = atmLoc;
            this.anchorSlot = anchorSlot;
        }
    }

    private static int indexOfAction(ActionType a) {
        for (int i = 0; i < ACTIONS.length; i++) if (ACTIONS[i] == a) return i;
        return 0;
    }

    private int optionsCountFor(BrowseSession s) {
        return switch (s.stage) {
            case ACTION -> ACTIONS.length;
            case WD_KIND, TARGET_MODE, PREFS_MENU -> 2;
            case TARGET_LIST -> Math.max(1, s.listCache.size());
        };
    }

    /**
     * Hotbar slots are a 0..8 ring. A naive sign(next - prev) breaks the
     * moment a jump crosses the 0/8 seam — e.g. three forward notches from
     * slot 7 (7->8->0->1) is prev=7,next=1, a raw diff of -6, whose sign
     * flips to "backward" even though every individual notch was forward.
     * That's exactly what fast scrolling could trigger (Bukkit coalescing
     * several notches into one event under load) and looked like random
     * reverts. Normalizing to the shortest arc on the ring (range (-4, 4])
     * before taking the sign fixes it for any realistic jump size.
     */
    private static int scrollDirection(int prev, int next) {
        int diff = next - prev;
        if (diff > 4) diff -= 9;
        if (diff < -4) diff += 9;
        return Integer.signum(diff);
    }

    private void endSession(Player p, BrowseSession s) {
        sessions.remove(p.getUniqueId());
        scroll.resumeScrolling(s.atmLoc);
        // resumeScrolling only re-arms the marquee TASK — and for a short
        // title (fits under maxLength, see makeSignScrollingLines) there
        // never was one to begin with, since it returns before creating a
        // task if nothing needs to scroll. Relying on it alone left ПКМ
        // (back-out to idle) with nothing that would ever repaint the
        // player's client — their view just stayed on whatever the last
        // browse render was, forever. Send the idle text ourselves instead
        // of assuming something else will.
        sendIdleView(p, s.atmLoc);
    }

    private void stepBack(Player p, BrowseSession s) {
        switch (s.stage) {
            case ACTION -> endSession(p, s);
            case WD_KIND, TARGET_MODE, PREFS_MENU -> {
                s.stage = BrowseStage.ACTION;
                s.index = (s.action != null) ? indexOfAction(s.action) : 0;
                renderBrowse(p, s);
            }
            case TARGET_LIST -> {
                s.stage = BrowseStage.TARGET_MODE;
                s.index = 1; // «Список» — так мы сюда и попали
                renderBrowse(p, s);
            }
        }
    }

    private void confirmCurrentSelection(Player p, BrowseSession s) {
        switch (s.stage) {
            case ACTION -> {
                ActionType chosen = ACTIONS[s.index];
                s.action = chosen;
                if (chosen == ActionType.INFO) {
                    endSession(p, s);
                    showInfo(p, s.atmLoc, store.get(s.atmLoc));
                    return;
                }
                if (chosen == ActionType.BALANCE) {
                    endSession(p, s);
                    showBalance(p);
                    return;
                }
                if (chosen == ActionType.PREFERENCES) {
                    var prefs = autoDebit.getPreferencesBlocking(p.getName());
                    s.prefsAutoDebit = prefs.autoDebitEnabled();
                    s.prefsPriority = prefs.paymentPriority();
                    s.stage = BrowseStage.PREFS_MENU;
                    s.index = 0;
                    renderBrowse(p, s);
                    return;
                }
                if (chosen == ActionType.WITHDRAW || chosen == ActionType.DEPOSIT) {
                    s.stage = BrowseStage.WD_KIND;
                    s.index = 0;
                    renderBrowse(p, s);
                    return;
                }
                s.stage = BrowseStage.TARGET_MODE;
                s.index = 0;
                renderBrowse(p, s);
            }
            case WD_KIND -> {
                s.kind = (s.index == 0) ? TargetKind.PLAYER : TargetKind.COUNTRY;
                s.targetValue = null;
                beginAmountEdit(p, s, false);
            }
            case TARGET_MODE -> {
                if (s.index == 0) {
                    beginAmountEdit(p, s, true);
                    return;
                }
                s.kind = (s.action == ActionType.TRANSFER_PLAYER) ? TargetKind.PLAYER : TargetKind.COUNTRY;
                s.listCache = (s.kind == TargetKind.PLAYER) ? registeredPlayerNamesExcept(p) : countryNamesWithMineFirst(p);
                s.stage = BrowseStage.TARGET_LIST;
                s.index = 0;
                renderBrowse(p, s);
            }
            case TARGET_LIST -> {
                if (s.listCache.isEmpty()) {
                    p.sendActionBar(ChatColor.RED + "Список пуст.");
                    return;
                }
                String picked = s.listCache.get(s.index);
                if (s.action == ActionType.TRANSFER_PLAYER && picked.equalsIgnoreCase(p.getName())) {
                    p.sendActionBar(ChatColor.RED + "Нельзя перевести самому себе.");
                    return;
                }
                s.kind = (s.action == ActionType.TRANSFER_PLAYER) ? TargetKind.PLAYER : TargetKind.COUNTRY;
                s.targetValue = picked;
                beginAmountEdit(p, s, false);
            }
            case PREFS_MENU -> {
                if (s.index == 0) {
                    s.prefsAutoDebit = !s.prefsAutoDebit;
                    autoDebit.setAutoDebitEnabled(p.getName(), s.prefsAutoDebit);
                } else {
                    s.prefsPriority = "BANK".equalsIgnoreCase(s.prefsPriority) ? "CASH" : "BANK";
                    autoDebit.setPaymentPriority(p.getName(), s.prefsPriority);
                }
                renderBrowse(p, s); // остаёмся в меню настроек — переключатели, не шаги мастера
            }
        }
    }

    private void renderBrowse(Player p, BrowseSession s) {
        SignVariables sv = store.get(s.atmLoc);
        String country = (sv != null) ? countryDisplayName(sv.getOwnerCountry()) : null;
        if (country == null) country = "?";
        String[] lines = new String[4];

        // GH #6: ACTION/WD_KIND/TARGET_MODE/PREFS_MENU are scrollable
        // choices too (colесом, same as TARGET_LIST/SHOP_LIST) — they just
        // never got the ▴/▾ affordance because each only ever had a fixed
        // small option set instead of a real list to page through.
        switch (s.stage) {
            case ACTION -> {
                lines[0] = trim("ATM [" + country + "]");
                lines[1] = "▴";
                lines[2] = trim(ACTION_LABELS[s.index]);
                lines[3] = "▾";
            }
            case WD_KIND -> {
                lines[0] = trim(ACTION_LABELS[indexOfAction(s.action)]);
                lines[1] = "▴";
                lines[2] = trim(s.index == 0 ? "Свой счёт" : "Казна");
                lines[3] = "▾";
            }
            case TARGET_MODE -> {
                lines[0] = trim(ACTION_LABELS[indexOfAction(s.action)]);
                lines[1] = "▴";
                lines[2] = trim(s.index == 0 ? "Вручную" : "Список");
                lines[3] = "▾";
            }
            case TARGET_LIST -> {
                int n = s.listCache.size();
                lines[0] = trim(ACTION_LABELS[indexOfAction(s.action)]);
                // Стрелочки над/под выбранным пунктом (строки 1 и 3) — чтобы
                // было видно, что список вообще можно прокрутить колесом, а
                // не только что где-то есть счётчик. Ring-список (кольцевой,
                // см. s.index = Math.floorMod(...) в onItemHeld), так что при
                // >1 пункте вверх/вниз всегда есть куда крутить — обе
                // стрелочки показываются одновременно.
                // GH #6: ▴/▾ everywhere now, not ▲/▼ — matches SignRenderer's
                // SHOP_LIST arrows so every scrollable sign list uses the same glyphs.
                lines[1] = n > 1 ? ("▴  (" + (s.index + 1) + "/" + n + ")") : "";
                lines[2] = trim(s.listCache.isEmpty() ? "(пусто)" : s.listCache.get(s.index));
                lines[3] = n > 1 ? "▾" : "";
            }
            case PREFS_MENU -> {
                lines[0] = trim("Настройки");
                lines[1] = "▴";
                lines[2] = trim(s.index == 0
                        ? ("Авто-спис: " + (s.prefsAutoDebit ? "ВКЛ" : "выкл"))
                        : ("Оплата: " + ("BANK".equalsIgnoreCase(s.prefsPriority) ? "Счёт" : "Нал.")));
                lines[3] = "▾";
            }
        }
        // Отправляем со сдвигом на тик, а не прямо сейчас: клик по табличке
        // сам по себе, судя по всему, заставляет клиент запросить/получить
        // повторную синхронизацию блока (обычное поведение при взаимодействии
        // с block entity) — если наш пакет уходит в тот же момент, он может
        // прийти РАНЬШЕ этой синхронизации и тут же перезатереться ею, и
        // именно это выглядело как "коснитесь" на долю секунды при каждом
        // подтверждении выбора. Сдвиг на тик почти гарантирует, что наш
        // пакет придёт последним.
        sendSignChangeNextTick(p, s.atmLoc, lines);
    }

    private void sendSignChangeNextTick(Player p, Location loc, String[] lines) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) p.sendSignChange(loc, lines);
        });
    }

    private void sendIdleView(Player p, Location atmLoc) {
        if (p == null || !p.isOnline()) return;
        SignVariables sv = store.get(atmLoc);
        List<String> idle = safe4(sv != null ? sv.getSignText() : null);
        sendSignChangeNextTick(p, atmLoc, idle.toArray(new String[0]));
    }

    /** sv.getOwnerCountry() исторически хранит Countries.Id (см. UpgradeCondition.playerCountryCanonical), не имя — отображать напрямую нельзя. */
    private static String countryDisplayName(String ownerCountryCanonical) {
        if (ownerCountryCanonical == null || ownerCountryCanonical.isBlank()) return null;
        try {
            return UnityLauncher.getInstance().countryRegistryJdbc.getCountryDisplayNameForCanonical(ownerCountryCanonical);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String trim(String s) {
        if (s == null) return "";
        return s.length() <= 15 ? s : s.substring(0, 15);
    }

    // ===== amount / target entry via real sign-edit screen =====

    private void beginAmountEdit(Player p, BrowseSession s, boolean manualTarget) {
        Location atmLoc = s.atmLoc;
        ActionType action = s.action;
        TargetKind kind = s.kind;
        String targetValue = s.targetValue;

        // Просмотр окончен — дальше ввод идёт через реальный экран
        // редактирования таблички, колесо мыши игроку возвращаем сразу.
        sessions.remove(p.getUniqueId());

        if (!(atmLoc.getBlock().getState() instanceof Sign sign)) {
            scroll.resumeScrolling(atmLoc);
            return;
        }

        if (manualTarget) {
            String promptTop = (action == ActionType.TRANSFER_PLAYER) ? "Ник игрока:" : "Страна:";
            sign.setLine(0, trim(promptTop));
            sign.setLine(1, "");
            sign.setLine(2, "Сумма:");
            sign.setLine(3, "");
        } else {
            String targetLabel = (targetValue != null) ? targetValue
                    : (kind == TargetKind.COUNTRY ? "Казна страны" : "Свой счёт");
            sign.setLine(0, trim(ACTION_LABELS[indexOfAction(action)]));
            sign.setLine(1, trim(targetLabel));
            sign.setLine(2, "Сумма:");
            sign.setLine(3, "");
        }
        sign.update();

        long expires = System.currentTimeMillis() + EDIT_TIMEOUT_MS;
        pendingEdits.put(p.getUniqueId(), new PendingSignEdit(atmLoc, action, kind, targetValue, manualTarget, expires));

        // openSign нельзя звать сразу вслед за update() — судя по всему,
        // клиент открывает экран редактирования с тем текстом, который уже
        // знал ДО того, как успел обработать наше только что отправленное
        // обновление блока (та же гонка пакетов, что и в renderBrowse выше),
        // из-за чего в полях редактирования оставался предыдущий шаг вместо
        // только что записанных подсказок. Небольшая задержка даёт update()
        // время дойти первым.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) p.openSign(sign, Side.FRONT);
        }, 2L);
    }

    /** Вызывается из SignManager.onSignChange для табличек категории ATM (существующих, не только что созданных). */
    public void onSignEditSubmit(SignChangeEvent e, Location atmLoc, SignVariables sv) {
        Player p = e.getPlayer();
        String[] submitted = e.getLines().clone(); // берём то, что реально напечатал игрок, ДО перезаписи ниже

        PendingSignEdit pending = pendingEdits.remove(p.getUniqueId());

        // Что бы ни было напечатано — оно никогда не должно стать постоянным текстом таблички.
        List<String> idle = safe4(sv != null ? sv.getSignText() : null);
        for (int i = 0; i < 4; i++) e.setLine(i, idle.get(i));
        scroll.resumeScrolling(atmLoc);

        if (pending == null || !atmLoc.equals(pending.atmLoc())) return; // просрочено (таймаут) или чужой стейт

        String targetValue = pending.targetValue();
        double amount;

        if (pending.needsTargetInput()) {
            String rawTarget = safeIdx(submitted, 1).trim();
            if (rawTarget.isBlank()) { p.sendActionBar(ChatColor.RED + "Не указана цель."); return; }
            Double amt = parseAmountForSign(safeIdx(submitted, 3), p);
            if (amt == null) return;
            targetValue = rawTarget;
            amount = amt;
        } else {
            Double amt = parseAmountForSign(safeIdx(submitted, 3), p);
            if (amt == null) return;
            amount = amt;
        }

        runActionWithResolvedTarget(p, atmLoc, pending.action(), pending.kind(), targetValue, amount);
    }

    private static String safeIdx(String[] arr, int i) {
        return (arr != null && i >= 0 && i < arr.length && arr[i] != null) ? arr[i] : "";
    }

    private Double parseAmountForSign(String raw, Player p) {
        String s = ChatColor.stripColor(raw == null ? "" : raw).trim().replace(',', '.');
        try {
            double v = Double.parseDouble(s);
            if (v <= 0) { p.sendActionBar(ChatColor.RED + "Сумма должна быть > 0."); return null; }
            return v;
        } catch (NumberFormatException ex) {
            p.sendActionBar(ChatColor.RED + "Введи число в поле суммы.");
            return null;
        }
    }

    private static List<String> safe4(List<String> in) {
        ArrayList<String> out = new ArrayList<>(4);
        if (in != null) out.addAll(in);
        while (out.size() < 4) out.add("");
        if (out.size() > 4) out.subList(4, out.size()).clear();
        return out;
    }

    private record PendingSignEdit(Location atmLoc, ActionType action, TargetKind kind, String targetValue,
                                    boolean needsTargetInput, long expiresAtMs) {}

    // ===== watchdog: ends a browse session once the player looks away, and
    // force-restores a sign stuck showing edit prompts if its screen was
    // ever abandoned without submitting (see EDIT_TIMEOUT_MS) =====

    private void startWatchdog() {
        watchdogTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickWatchdog, 20L, 4L);
    }

    private void tickWatchdog() {
        if (!sessions.isEmpty()) {
            Iterator<Map.Entry<UUID, BrowseSession>> it = sessions.entrySet().iterator();
            while (it.hasNext()) {
                var entry = it.next();
                Player p = Bukkit.getPlayer(entry.getKey());
                BrowseSession s = entry.getValue();
                if (p == null || !p.isOnline()) {
                    it.remove();
                    scroll.resumeScrolling(s.atmLoc);
                    continue;
                }
                if (!stillLookingAt(p, s.atmLoc)) {
                    it.remove();
                    scroll.resumeScrolling(s.atmLoc);
                    sendIdleView(p, s.atmLoc);
                    continue;
                }
                // sendSignChange is a one-off client-side override, not a
                // sticky one — any real resync of that block (chunk resend,
                // a nearby block update, anything) silently reverts the
                // player's view back to the sign's actual (idle) text. That
                // was the "confirming a choice dumps me back on 'Коснитесь'
                // until I scroll" bug: the session WAS advancing correctly
                // server-side, the client's view of it just kept getting
                // clobbered. Re-asserting it on every watchdog tick (5/s)
                // keeps it pinned regardless of what else touches the block.
                renderBrowse(p, s);
            }
        }

        if (!pendingEdits.isEmpty()) {
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<UUID, PendingSignEdit>> it = pendingEdits.entrySet().iterator();
            while (it.hasNext()) {
                var entry = it.next();
                PendingSignEdit pe = entry.getValue();
                if (now < pe.expiresAtMs()) continue;
                it.remove();
                restoreIdleDisplay(pe.atmLoc());
                scroll.resumeScrolling(pe.atmLoc());
            }
        }
    }

    private boolean stillLookingAt(Player p, Location atmLoc) {
        try {
            if (!p.getWorld().equals(atmLoc.getWorld())) return false;
            if (p.getLocation().distanceSquared(atmLoc) > MAX_SESSION_DISTANCE_SQ) return false;
            var target = p.getTargetBlockExact(LOOK_RANGE, FluidCollisionMode.NEVER);
            return target != null && SignStore.keyLoc(target.getLocation()).equals(atmLoc);
        } catch (Throwable t) {
            return false; // на любую неожиданность лучше тихо закрыть сессию, чем зависнуть навсегда
        }
    }

    private void restoreIdleDisplay(Location atmLoc) {
        SignVariables sv = store.get(atmLoc);
        if (!(atmLoc.getBlock().getState() instanceof Sign sign)) return;
        List<String> idle = safe4(sv != null ? sv.getSignText() : null);
        for (int i = 0; i < 4; i++) sign.setLine(i, idle.get(i));
        sign.update();
    }

    // ===== target-list sources =====

    private List<String> registeredPlayerNamesExcept(Player p) {
        List<String> names = new ArrayList<>(UnityLauncher.getInstance().getAuthService().getAllRegisteredUsernames());
        names.removeIf(n -> n.equalsIgnoreCase(p.getName()));
        return names;
    }

    private List<String> countryNamesWithMineFirst(Player p) {
        List<String> all = new ArrayList<>(UnityLauncher.getInstance().countryRegistryJdbc.getAllCountryNames());
        String mine = UnityLauncher.getInstance().countryRegistryJdbc.getCountryOfPlayer(p.getName());
        if (mine != null && !mine.isBlank()) {
            all.removeIf(c -> c.equalsIgnoreCase(mine));
            all.add(0, mine);
        }
        return all;
    }

    // ===== operations (money-moving logic — unchanged from the chest-GUI version) =====

    private boolean runActionWithResolvedTarget(Player p, Location atmLoc, ActionType action, TargetKind kind, String targetValue, double amount) {
        if (p == null || atmLoc == null) return true;
        if (amount <= 0) return false;

        switch (action) {
            case WITHDRAW -> {
                if (kind == TargetKind.PLAYER) {
                    // Единая модель (см. atmFeeRate/grossFor ниже, в ===== utils =====):
                    // счёт списывается на gross (amount + комиссия), наличными игрок
                    // получает amount целиком — комиссию платит источник денег, а
                    // не получатель.
                    double rate = atmFeeRate(p, atmLoc, amount);
                    double gross = grossFor(amount, rate);
                    announceFee(p, amount, rate);
                    p.sendMessage(ChatColor.GRAY + "Обрабатываем снятие...");
                    plugin.moneyManager.withdrawToCashBurnFee(p, gross, amount, atmLoc);
                    return true;
                }
                if (kind == TargetKind.COUNTRY) {
                    if (!UnityLauncher.getInstance().countryRegistryJdbc.isCountryLeaderCached(p.getName())) {
                        p.sendMessage(ChatColor.RED + "Снимать из казны может только лидер страны.");
                        return false;
                    }
                    String myCountry = UnityLauncher.getInstance().countryRegistryJdbc.getCountryOfPlayer(p.getName());
                    if (myCountry == null || myCountry.isBlank()) {
                        p.sendMessage(ChatColor.RED + "Ты не состоишь в стране.");
                        return false;
                    }
                    p.sendMessage(ChatColor.GRAY + "Проверяем казну страны...");

                    double rate = atmFeeRate(p, atmLoc, amount);
                    double gross = grossFor(amount, rate);

                    new BukkitRunnable() {
                        @Override public void run() {
                            double countryMoney = UnityLauncher.getInstance().countryRegistryJdbc.getCountryMoney(myCountry);
                            if (countryMoney < gross) {
                                new BukkitRunnable(){ @Override public void run(){
                                    p.sendMessage(ChatColor.RED + "В казне недостаточно средств. Доступно: "
                                            + ChatColor.YELLOW + round2(countryMoney) + ChatColor.RED + " Ⓕ.");
                                }}.runTask(UnityLauncher.getInstance());
                                return;
                            }

                            UnityLauncher.getInstance().countryRegistryJdbc.addCountryMoney(myCountry, -gross);

                            new BukkitRunnable(){ @Override public void run(){
                                // Казна платит gross, игрок получает запрошенную сумму целиком.
                                announceFee(p, amount, rate);
                                plugin.moneyManager.giveCash(p, amount);
                                p.sendMessage(ChatColor.GREEN + "Снято из казны: " + ChatColor.YELLOW + round2(amount)
                                        + ChatColor.GREEN + " Ⓕ (" + ChatColor.AQUA + myCountry + ChatColor.GREEN + ")");
                            }}.runTask(UnityLauncher.getInstance());
                        }
                    }.runTaskAsynchronously(UnityLauncher.getInstance());

                    return true;
                }
            }

            case DEPOSIT -> {
                // Из инвентаря уходит gross, на счёт/в казну зачисляется amount целиком.
                double rate = atmFeeRate(p, atmLoc, amount);
                double gross = grossFor(amount, rate);
                announceFee(p, amount, rate);

                if (kind == TargetKind.PLAYER) {
                    p.sendMessage(ChatColor.GRAY + "Вносим наличку на счёт...");
                    return plugin.moneyManager.depositCashToPlayerBurnFee(p, gross, amount, atmLoc);
                }
                if (kind == TargetKind.COUNTRY) {
                    p.sendMessage(ChatColor.GRAY + "Вносим наличку в казну...");
                    return plugin.moneyManager.depositCashToCountryBurnFee(p, gross, amount);
                }
                return true;
            }

            case TRANSFER_PLAYER -> {
                if (targetValue == null || targetValue.isBlank()) {
                    p.sendMessage(ChatColor.RED + "Не выбран игрок.");
                    return false;
                }
                return handleTransferPlayerResolved(p, atmLoc, targetValue, amount);
            }

            case TRANSFER_COUNTRY -> {
                if (targetValue == null || targetValue.isBlank()) {
                    p.sendMessage(ChatColor.RED + "Не выбрана страна.");
                    return false;
                }
                return handleTransferCountryResolved(p, atmLoc, targetValue, amount);
            }
        }

        return true;
    }

    private boolean handleTransferPlayerResolved(Player p, Location atmLoc, String targetName, double amount) {
        if (targetName.equalsIgnoreCase(p.getName())) { p.sendMessage(ChatColor.RED + "Нельзя перевести самому себе."); return false; }

        p.sendMessage(ChatColor.GRAY + "Проверяем данные и выполняем перевод...");

        new BukkitRunnable() {
            @Override public void run() {
                List<String> keys = List.of("money");
                Map<String, Object> senderMap = UnityCommands.getInstance()
                        .getJsonFieldValues("Users", "GeneralData", "Name", p.getName(), keys);
                Double senderMoney = senderMap.get("money") instanceof Number ? ((Number) senderMap.get("money")).doubleValue() : null;

                UnityCommands.getInstance().getPlayerInfo(targetName, targetData -> {
                    if (senderMoney == null) {
                        new BukkitRunnable(){ @Override public void run(){
                            p.sendMessage(ChatColor.RED + "Не удалось получить твой баланс.");
                        }}.runTask(UnityLauncher.getInstance());
                        return;
                    }
                    if (targetData == null) {
                        new BukkitRunnable(){ @Override public void run(){
                            p.sendMessage(ChatColor.RED + "Игрок '" + targetName + "' не найден.");
                        }}.runTask(UnityLauncher.getInstance());
                        return;
                    }

                    // Единая модель, зеркалит сайтовый /bank/transfer: получатель
                    // получает amount ЦЕЛИКОМ, отправитель платит gross = amount + комиссия.
                    double rate = atmFeeRate(p, atmLoc, amount);
                    double fee = round2(amount * rate);
                    double gross = round2(amount + fee);

                    if (senderMoney < gross) {
                        new BukkitRunnable(){ @Override public void run(){
                            p.sendMessage(ChatColor.RED + "Недостаточно средств. Нужно: " + ChatColor.YELLOW + round2(gross)
                                    + ChatColor.RED + " Ⓕ" + (fee > 0 ? (ChatColor.GRAY + " (из них комиссия " + ChatColor.RED + fee + " Ⓕ" + ChatColor.GRAY + ")") : "")
                                    + ChatColor.RED + ". Доступно: " + ChatColor.YELLOW + senderMoney + ChatColor.RED + " Ⓕ.");
                        }}.runTask(UnityLauncher.getInstance());
                        return;
                    }

                    new BukkitRunnable(){ @Override public void run(){
                        UnityCommands uc = UnityCommands.getInstance();

                        Map<String, Object> updSender = new HashMap<>();
                        updSender.put("money", round2(senderMoney - gross));
                        uc.mergeAndUpdatePlayerData(p.getName(), "GeneralData", updSender);

                        Map<String, Object> updTarget = new HashMap<>();
                        updTarget.put("money", round2(targetData.money + amount));
                        uc.mergeAndUpdatePlayerData(targetName, "GeneralData", updTarget);

                        new BukkitRunnable(){ @Override public void run(){
                            p.sendMessage(ChatColor.GREEN + "Перевод выполнен: " + ChatColor.YELLOW + round2(amount) + " Ⓕ"
                                    + ChatColor.GREEN + " → " + ChatColor.RESET + targetName
                                    + (fee > 0 ? (ChatColor.GRAY + " (получатель получил " + ChatColor.YELLOW + round2(amount) + " Ⓕ"
                                    + ChatColor.GRAY + " целиком, с тебя списано " + ChatColor.RED + round2(gross) + " Ⓕ"
                                    + ChatColor.GRAY + ", комиссия " + ChatColor.RED + fee + " Ⓕ" + ChatColor.GRAY + ")") : ""));
                        }}.runTask(UnityLauncher.getInstance());

                    }}.runTaskAsynchronously(UnityLauncher.getInstance());
                });
            }
        }.runTaskAsynchronously(UnityLauncher.getInstance());

        return true;
    }

    private boolean handleTransferCountryResolved(Player p, Location atmLoc, String targetCountry, double amount) {
        if (!UnityLauncher.getInstance().getCountryRegistryJdbc().countryExistsCached(targetCountry)) {
            p.sendMessage(ChatColor.RED + "Страна '" + targetCountry + "' не найдена.");
            return false;
        }

        p.sendMessage(ChatColor.GRAY + "Выполняем перевод стране...");

        // Та же модель: страна-получатель зачисляет amount целиком, отправитель
        // платит gross = amount + комиссия со своего счёта.
        double rate = atmFeeRate(p, atmLoc, amount);
        final double fee = round2(amount * rate);
        final double gross = round2(amount + fee);

        plugin.moneyManager.tryWithdraw(p.getName(), gross, ok -> {
            if (!ok) {
                new BukkitRunnable() { @Override public void run() {
                    p.sendMessage(ChatColor.RED + "Недостаточно средств на счёте для перевода"
                            + (fee > 0 ? (" (нужно " + round2(gross) + " Ⓕ с учётом комиссии " + fee + " Ⓕ)") : "") + ".");
                }}.runTask(UnityLauncher.getInstance());
                return;
            }

            new BukkitRunnable() {
                @Override public void run() {
                    UnityLauncher.getInstance().countryRegistryJdbc.addCountryMoney(targetCountry, amount);

                    new BukkitRunnable() { @Override public void run() {
                        p.sendMessage(
                                ChatColor.GREEN + "Перевод выполнен: " +
                                        ChatColor.YELLOW + round2(amount) + " Ⓕ" +
                                        ChatColor.GREEN + " → " + ChatColor.AQUA + targetCountry +
                                        (fee > 0
                                                ? ChatColor.GRAY + " (зачислено целиком, с тебя списано " + ChatColor.RED + round2(gross) +
                                                ChatColor.GRAY + " Ⓕ, комиссия " + ChatColor.RED + fee + " Ⓕ)"
                                                : "")
                        );
                    }}.runTask(UnityLauncher.getInstance());
                }
            }.runTaskAsynchronously(UnityLauncher.getInstance());
        });

        return true;
    }

    private void showInfo(Player p, Location atmLoc, SignVariables sv) {
        double rate = 0.0;
        try { rate = atmFeeRate(p, atmLoc, 100.0); } catch (Throwable ignored) {}

        String owner = (sv != null && sv.getOwnerName() != null) ? sv.getOwnerName() : "?";
        String country = (sv != null) ? countryDisplayName(sv.getOwnerCountry()) : null;
        if (country == null) country = "?";

        // GH #6: dropped the "(получатель получает сумму целиком...)" line —
        // redundant/confusing next to the commission % already shown above.
        p.sendMessage(ChatColor.YELLOW + "=======[ ATM ]=======\n" +
                ChatColor.GREEN + "Принадлежит: " + ChatColor.RESET + country + "\n" +
                ChatColor.GREEN + "Установлен: " + ChatColor.RESET + owner + "\n" +
                ChatColor.GREEN + "Комиссия ATM сейчас: " + ChatColor.YELLOW + String.format(Locale.ROOT, "%.1f", rate * 100.0) + "%"
        );
    }

    private void showBalance(Player p) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<String, Object> map = UnityCommands.getInstance()
                    .getJsonFieldValues("Users", "GeneralData", "Name", p.getName(), List.of("money"));
            Double bal = map.get("money") instanceof Number ? ((Number) map.get("money")).doubleValue() : null;

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (bal == null) {
                    p.sendMessage(ChatColor.RED + "Не удалось получить баланс.");
                    return;
                }
                p.sendMessage(ChatColor.YELLOW + "Твой баланс: " + ChatColor.GREEN + round2(bal) + " Ⓕ");
            });
        });
    }

    // ===== utils =====

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private AtmFeesUpgrade atmFeesUpgradeOrNull() {
        try {
            var um = plugin.getUpgradesManager();
            if (um == null) return null;
            return um.getEnabled(AtmFeesUpgrade.class);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ===== единая модель комиссии: получатель получает amount целиком, =====
    // ===== источник платит gross = amount + комиссия сверху ===============
    // Зеркалит backend/src/routes/bank.ts (сайтовый /bank/transfer) — три
    // уровня ставки считает AtmFeesUpgrade.calculateAtmFee, здесь только
    // домножение до gross и (где уместно) чат-уведомление о комиссии.

    /** Ставка [0,1] для данного ATM/суммы — без побочных эффектов (без сообщений). */
    private double atmFeeRate(Player actor, Location atmLoc, double amount) {
        if (actor == null || atmLoc == null || amount <= 0.0) return 0.0;
        AtmFeesUpgrade u = atmFeesUpgradeOrNull();
        if (u == null) return 0.0;
        return u.calculateAtmFee(actor.getName(), atmLoc, amount);
    }

    /** Что спишется с источника, чтобы получатель получил amount целиком. */
    private double grossFor(double amount, double rate) {
        return round2(amount + amount * rate);
    }

    // ConcurrentHashMap, not HashMap: announceFee is reached both from the
    // main-thread click handler and from async-runnable success callbacks
    // (see WITHDRAW/COUNTRY above) — matches the thread-safety the old
    // AtmFeesUpgrade.msgCooldown had before this moved here.
    private final Map<UUID, Long> feeMsgCooldown = new java.util.concurrent.ConcurrentHashMap<>();

    /** "Комиссия банкомата: X (Y%)" — не чаще раза в 5с на игрока. Только для операций
     *  без собственного inline-упоминания комиссии (переводы формируют его сами). */
    private void announceFee(Player p, double amount, double rate) {
        if (rate <= 1e-9) return;
        double fee = round2(amount * rate);
        if (fee < 0.01) return;
        long now = System.currentTimeMillis();
        Long last = feeMsgCooldown.get(p.getUniqueId());
        if (last != null && (now - last) <= 5000) return;
        feeMsgCooldown.put(p.getUniqueId(), now);
        p.sendMessage(ChatColor.YELLOW + "Комиссия банкомата: " + ChatColor.RED
                + String.format(Locale.ROOT, "%.2f", fee) + " Ⓕ " + ChatColor.GRAY
                + "(" + String.format(Locale.ROOT, "%.1f", rate * 100.0) + "%)");
    }

    private enum ActionType {
        WITHDRAW,
        DEPOSIT,
        TRANSFER_PLAYER,
        TRANSFER_COUNTRY,
        BALANCE,
        PREFERENCES,
        INFO
    }

    private enum TargetKind {
        PLAYER,
        COUNTRY
    }
}
