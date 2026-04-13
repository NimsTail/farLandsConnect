# Система апгрейдов - Краткая сводка

## ✅ Полностью реализовано и работает

### Структура системы

```
UpgradesConfig.java          - Конфигурация всех апгрейдов
UpgradesListener.java        - Industrial + Fields + Colony (22 апгрейда)
BankUpgradesManager.java     - Bank (6 апгрейдов)
ParkUpgradesManager.java     - Park (5 апгрейдов)
HospitalUpgradesManager.java - Hospital (7 апгрейдов)
LibraryUpgradesManager.java  - Library (4 апгрейда)
StateUpgradesManager.java    - State/Country (12 апгрейдов)
StateUpgradesCommands.java   - Команды для государственных апгрейдов
```

### Распределение по файлам

**UpgradesListener.java** (исходный файл):
- ✅ Industrial (15): Редстоун, воронки, зельеварка, печи, незерит, маяк, и т.д.
- ✅ Fields/Greenhouse (7): Элитная еда, размножение, грядки, опыление, гидропоника
- ✅ Colony (4): Форпост, продпай, ТНТ-лицензия

**Новые менеджеры** (созданы для организации):
- ✅ Bank (6): Сейфы, банкоматы, проценты
- ✅ Park (5): Садовод, тишина, сатурация
- ✅ Hospital (7): Психподдержка, диета, регенерация, дар крови
- ✅ Library (4): Экономия зачарований, спокойствие, образование
- ✅ State (12): Контракты, налоги, пошлины, фокус, пропаганда, цензура

## 📊 Статистика

| Всего апгрейдов | Работают автоматически | Готовы API | Требуют доработки |
|-----------------|------------------------|------------|-------------------|
| **67** | **58** (87%) | **9** (13%) | **6 Church** (9%) |

## 🎯 Как они используются

### 1. Автоматические (58 апгрейдов)
**Работают сразу после выдачи permission:**
- Обработчики событий Bukkit
- Периодические таски (воронки, регенерация, проценты, и т.д.)
- Блокировка/разрешение действий (редстоун, незерит, еда)

**Примеры:**
```yaml
# Выдать стране апгрейд
lp group country_russia permission set unity.upgrade.redstone.1 true
lp group country_russia permission set unity.bank.safe true
```

### 2. API для интеграции (9 апгрейдов)
**Требуют вызова методов из других систем:**

```java
// Налог на роскошь (SignManager)
double tax = stateManager.calculateLuxuryTax(country, item, price);

// Экспортный ребейт (SignManager)
double rebate = stateManager.calculateExportRebate(seller, location, price);

// Happy Hour (SignManager)
int discount = stateManager.getHappyHourDiscount(shopLocation);

// Торговая зона (SignManager)
int extraSlots = stateManager.getExtraShopSlots(country);

// Банкоматы (ATM система)
double fee = bankManager.calculateAtmFee(player, location, amount);

// Образование (Система квестов)
double reward = libraryManager.applyEducationBonus(player, baseReward);
```

### 3. Команды игрокам
```
/brand - маркировка предметов производителем
/state contract create <описание> <награда>
/state contract list
/state focus <fish|wood|ore>
/state sampler
/state happyhour <start> <end> <discount>
/state recycle
```

## 🔧 Интеграция с существующими системами

### SignManager - торговля
**Добавить в код обработки продажи/покупки:**
```java
StateUpgradesManager state = plugin.getStateUpgradesManager();

// При продаже
double luxuryTax = state.calculateLuxuryTax(country, item, price);
double rebate = state.calculateExportRebate(sellerCountry, shopLoc, price);

// При покупке
int discount = state.getHappyHourDiscount(shopLocation);
finalPrice = price * (1.0 - discount / 100.0);

// При создании магазина
int limit = baseLimit + state.getExtraShopSlots(country);

// Проверка образца
if (state.isSampleItem(item)) {
    // заблокировать продажу
}
```

### ATM система
**Добавить в код банкомата:**
```java
BankUpgradesManager bank = plugin.getBankUpgradesManager();
double amountAfterFee = bank.applyAtmFee(player, atmLoc, amount);
```

### Система квестов
**Добавить при выдаче награды:**
```java
LibraryUpgradesManager library = plugin.getLibraryUpgradesManager();
double finalReward = library.applyEducationBonus(player, baseReward);
```

## 📖 Документация

- **UPGRADES_CATALOG.md** - Полная таблица всех 67 апгрейдов с обработчиками
- **UPGRADES_INTEGRATION.md** - Детальное руководство по интеграции API
- **UPGRADES_SUMMARY.md** - Этот файл (краткая сводка)

## ⚠️ TODO (6 апгрейдов церкви)

Требуется создать `ChurchUpgradesManager.java`:
1. Pilgrimage - бафф при посещении
2. Pilgrim Protection - -5% урона
3. Festival of Lights - ивент с наградами
4. Spark Return - сохранение XP
5. Night's Rest - отключение фантомов (частично есть)
6. Holy Aura - горение нежити

## 🎮 Как добавить новый апгрейд

### 1. Добавить конфиг в UpgradesConfig.java

```md
### 1. Добавить конфиг в UpgradesConfig.java
Выбирай секцию по категории апгрейда (industrial/fields/bank/…).
Пример для industrial:

```java
public final String myUpgradePerm;
public final int myUpgradeValue;

// В конструкторе (чтение):
myUpgradePerm  = cfg.getString("industrial.myUpgrade.perm", "unity.my.upgrade");
myUpgradeValue = cfg.getInt("industrial.myUpgrade.value", 10);

// В addDefaults() (дефолты + описание отдельным ключом):
c.addDefault("industrial.myUpgrade.description", "Описание апгрейда");
c.addDefault("industrial.myUpgrade.perm", "unity.my.upgrade");
c.addDefault("industrial.myUpgrade.value", 10);

### 2. Добавить обработчик
**В существующий менеджер или UpgradesListener:**
```java
@EventHandler
public void onMyEvent(MyEvent e) {
    String country = UpgradeCondition.playerCountryCanonical(player.getName());
    if (countryMaxLevel(country, config.myUpgradePerm, 1) < 1) return;

    // Логика апгрейда
}
```

### 3. Документировать
Добавить в `UPGRADES_CATALOG.md`

## ✅ Проверка работоспособности

```bash
# В config.yml:
upgrades.debug: true

# Выдать апгрейд:
lp group country_test permission set unity.upgrade.redstone.1 true

# Проверить лог:
[UL/UpgradesListener] onBlockPlace: ...
```

## 🚀 Производительность

- **Оптимизированные кэши**: Все менеджеры используют ConcurrentHashMap
- **Таски работают асинхронно** где возможно
- **Проверки прав кэшируются** через LuckPerms
- **Периодические таски** имеют настраиваемые интервалы

## 📝 Лицензия и авторство

Все апгрейды разработаны специально для сервера FarLandsConnect.
