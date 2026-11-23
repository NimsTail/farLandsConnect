# Интеграция системы апгрейдов

## Обзор
Все апгрейды активно работают через обработчики событий и публичные API методы.

## Bank Upgrades (BankUpgradesManager)

### Автоматически работающие:
- **Safe Deposit Boxes**: События onSafeOpen, onSafeBreak блокируют доступ к чужим сейфам
- **Deposit Interest**: Таск каждый час начисляет проценты на деньги в сейфах
- **ATM Network**: Метод calculateAtmFee() готов к интеграции с SignManager

### API для интеграции:
```java
// Проверка активного эффекта
bankManager.hasBloodGift(player);

// Применение комиссии ATM
double fee = bankManager.calculateAtmFee(playerName, atmLocation, amount);
```

## Park Upgrades (ParkUpgradesManager)

### Автоматически работающие:
- **Gardener's Hut**: onPlantGrow ускоряет рост растений
- **Quiet Guard**: onGameEvent глушит звуки
- **Pond and Flowerbeds**: Таск каждые 5 секунд восстанавливает сатурацию
- **Quiet Hour**: onMobSpawn блокирует спавн монстров
- **Benches**: processBenchRegeneration дает регенерацию сидящим

### API для интеграции:
```java
// Проверка парка с апгрейдом
parkManager.isParkWithUpgrades(location, upgradePerm);
```

## Hospital Upgrades (HospitalUpgradesManager)

### Автоматически работающие:
- **Psych Support**: onPlayerDeath дает Luck I при смерти
- **Diet**: onPlayerEat добавляет бонус сатурации
- **Regen Pulse**: Таск каждые 30 секунд дает регенерацию
- **Sanitary Zone**: onMobSpawn сокращает спавн мобов
- **Blood Gift**: onPlayerLeaveBed дает +1 сердце после сна
- **Triage**: onPotionEffect сокращает длительность негативных эффектов

### API для интеграции:
```java
// Проверка активного дара крови
boolean hasBloodGift = hospitalManager.hasBloodGift(player);
long remainingSeconds = hospitalManager.getBloodGiftRemainingSeconds(player);
```

## Library Upgrades (LibraryUpgradesManager)

### Автоматически работающие:
- **Scrolls of Economy**: onPrepareEnchant и onEnchant снижают стоимость
- **Calm**: onFoodLevelChange замедляет трату голода

### API для интеграции с системой квестов:
```java
// Применение бонуса к квестовым наградам
double modifiedReward = libraryManager.applyEducationBonus(player, baseAmount);

// Проверка наличия бонуса
boolean hasBonus = libraryManager.hasEducationBonus(player);
double multiplier = libraryManager.getEducationBonusMultiplier(player);
```

## State Upgrades (StateUpgradesManager)

### Автоматически работающие:
1. **Toll Roads**: onPlayerMove взимает пошлину при пересечении границ
2. **Resource Focus**: onBlockBreak и onPlayerFish применяют бонусы/штрафы к добыче
3. **Propaganda**: Таск каждые 30 минут рассылает сообщения гражданам
4. **Censorship**: onPlayerChat и onSignChange фильтруют текст
5. **Curfew**: Таск каждую минуту применяет Slowness+Darkness ночью
6. **Repair Guild**: onPlayerBreakTool автоматически перерабатывает сломанные инструменты
7. **Sampler**: onSampleItemClick блокирует продажу образцов

### API для интеграции с торговлей:
```java
StateUpgradesManager stateManager = plugin.getStateUpgradesManager();

// Налог на роскошь (для SignManager при продаже)
double tax = stateManager.calculateLuxuryTax(countryName, item, salePrice);

// Экспортный ребейт (для SignManager при продаже)
double rebate = stateManager.calculateExportRebate(sellerCountry, saleLocation, salePrice);

// Торговая зона (для SignManager при создании магазинов)
int extraSlots = stateManager.getExtraShopSlots(countryName);

// Happy Hour (для SignManager при покупке)
int discount = stateManager.getHappyHourDiscount(shopLocation);

// Проверка образца
boolean isSample = stateManager.isSampleItem(item);
```

### Команды для игроков:
```
/state contract create <описание> <награда> - создать гос.заказ
/state contract list - список контрактов
/state focus <fish|wood|ore> - установить ресурсный фокус
/state sampler - проверить возможность взять образец
/state happyhour <start> <end> <discount> - установить happy hour
/state recycle - переработать сломанный инструмент
```

## Интеграция с SignManager

### Рекомендуемые изменения в SignManager:

1. **При продаже товара добавить:**
```java
// Налог на роскошь
StateUpgradesManager stateManager = plugin.getStateUpgradesManager();
double luxuryTax = stateManager.calculateLuxuryTax(countryName, item, price);
totalPrice += luxuryTax;

// Экспортный ребейт
double rebate = stateManager.calculateExportRebate(sellerCountry, shopLocation, price);
sellerProfit += rebate;
```

2. **При покупке добавить:**
```java
// Happy Hour
int discount = stateManager.getHappyHourDiscount(shopLocation);
if (discount > 0) {
    finalPrice = price * (1.0 - discount / 100.0);
}
```

3. **При создании магазина добавить:**
```java
// Торговая зона
int baseLimit = /* текущий лимит */;
int extraSlots = stateManager.getExtraShopSlots(countryName);
int totalLimit = baseLimit + extraSlots;
```

4. **При создании образца добавить:**
```java
// Пробник
if (stateManager.canTakeSample(player)) {
    ItemStack sample = stateManager.createSampleItem(originalItem);
    player.getInventory().addItem(sample);
    stateManager.recordSampleTaken(player);
}
```

## Интеграция с ATM (если есть отдельный обработчик)

```java
BankUpgradesManager bankManager = plugin.getBankUpgradesManager();
double fee = bankManager.calculateAtmFee(playerName, atmLocation, withdrawAmount);
double amountAfterFee = bankManager.applyAtmFee(player, atmLocation, withdrawAmount);
```

## Интеграция с системой квестов

```java
LibraryUpgradesManager libraryManager = plugin.getLibraryUpgradesManager();

// При выдаче награды за квест
double baseReward = 100.0;
double finalReward = libraryManager.applyEducationBonus(player, baseReward);
```

## Конфигурация

Все апгрейды настраиваются в `upgrades.yml` (файл плагина UnityLauncher).  
Ключи организованы по секциям:

- `core.*` — общие настройки (например, debug)
- `commands.*` — команды (например, `/brand`)
- `zones.*` — анлоки зон
- `industrial.*`, `fields.*`, `colony.*`, `bank.*`, `park.*`, `hospital.*`, `library.*`, `state.*`, `church.*`

Описание апгрейда хранится отдельным ключом `*.description` (потому что YAML-комментарии не сохраняются надёжно).

**Пример (Energy Saving):**
- `industrial.energySaving.description`
- `industrial.energySaving.perm`
- `industrial.energySaving.multiplier`

## Отладка

Установите `upgrades.debug: true` в config.yml для подробного логирования всех действий апгрейдов.
