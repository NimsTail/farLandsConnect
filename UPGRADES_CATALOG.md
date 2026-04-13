# Полный каталог апгрейдов

## Industrial (Индустриальная зона) - 15 апгрейдов

| №  | Название            | Описание                                         | Обработчик                                      | Статус     |
|----|---------------------|--------------------------------------------------|-------------------------------------------------|------------|
| 1  | Industrial Zone     | Возможность покупать индустриальные зоны         | ZoneManager                                     | ✅ Работает |
| 2  | Basic Redstone      | Открывается 1 часть редстоуна (repeater, torch)  | `UpgradesListener.onBlockPlace()`               | ✅ Работает |
| 3  | Smart Hoppers       | Ускорение воронок                                | `UpgradesListener.runTurboTick()` таск          | ✅ Работает |
| 4  | Alchemy             | Ускорение зельеварки на 25%                      | `UpgradesListener.brewTask` таск                | ✅ Работает |
| 5  | Motivation          | Эффект Haste в зоне                              | `UpgradesListener.onPlayerMove()`               | ✅ Работает |
| 6  | Eco Fuel            | Бамбук горит дольше                              | `UpgradesListener.onFurnaceBurn()`              | ✅ Работает |
| 7  | Dust Protection     | Night Vision под землёй (y<40)                   | `UpgradesListener.onPlayerMove()`               | ✅ Работает |
| 8  | Geothermal Boost    | Печи у лавы быстрее                              | `UpgradesListener.onFurnaceSmelt()`             | ✅ Работает |
| 9  | Industrial Recycler | Переработка блоков → редкие материалы            | `UpgradesListener.onBlockBreakRecycler()`       | ✅ Работает |
| 10 | Advanced Redstone   | 2 часть редстоуна (comparator, piston, observer) | `UpgradesListener.onBlockPlace()`               | ✅ Работает |
| 11 | Netherite Upgrade   | Открывает незерит                                | `UpgradesListener.onCraft()` + `onBlockPlace()` | ✅ Работает |
| 12 | Brand Marking       | Лор "произведено игроком"                        | Команда `/brand`                                | ✅ Работает |
| 13 | Beacon              | Открывает маяк                                   | `UpgradesListener.onCraft()` + `onBlockPlace()` | ✅ Работает |
| 14 | Energy Saving       | Механизмы потребляют -30% активности             | `ActivityTracker.getEnergySavingMultiplier()`   | ✅ Работает |
| 15 | Loader              | Ускорение вагонеток у медных блоков              | `UpgradesListener.onMinecartInventoryMove()`    | ✅ Работает |

**Config секция:** `upgrades.redstone.*`, `upgrades.hopper.*`, `upgrades.brew.*`, `upgrades.furnace.*`, и т.д.

---

## Fields/Greenhouse (Сельхоз-зона) - 7 апгрейдов

| № | Название         | Описание                              | Обработчик                                     | Статус     |
|---|------------------|---------------------------------------|------------------------------------------------|------------|
| 1 | Fields Zone      | Открывает сельхоз-зону                | ZoneManager                                    | ✅ Работает |
| 2 | Chef             | Элитная еда (золотая морковь, яблоко) | `UpgradesListener.onPlayerEat()` + `onCraft()` | ✅ Работает |
| 3 | Livestock+       | Ускорение размножения на 20-30%       | `UpgradesListener.onEntityBreed()`             | ✅ Работает |
| 4 | Livestock++      | 5% шанс двойни                        | `UpgradesListener.onEntityBreed()`             | ✅ Работает |
| 5 | Non-Trample Soil | Грядки не топчутся                    | `UpgradesListener.onEntityChangeBlock()`       | ✅ Работает |
| 6 | Bee Pollination  | Улей + грядки = бонус роста           | `UpgradesListener.onBlockGrow()`               | ✅ Работает |
| 7 | Hydroponics      | Рост при низком свете под стеклом     | `UpgradesListener.runCropsLowLightTick()` таск | ✅ Работает |

**Config секция:** `upgrades.goldenFood.*`, `upgrades.livestockPlus.*`, `upgrades.beePollination.*`, `upgrades.cropsLowLight.*`

---

## Colony (Колониальная зона) - 4 апгрейда

| № | Название    | Описание                                 | Обработчик                          | Статус     |
|---|-------------|------------------------------------------|-------------------------------------|------------|
| 1 | Colony Zone | Открывает колониальные зоны              | ZoneManager                         | ✅ Работает |
| 2 | Outpost     | Усиление против рейдов (-15% спавна)     | `UpgradesListener.onRaidMobSpawn()` | ✅ Работает |
| 3 | Food Ration | Периодический Saturation I каждые 30 сек | `ColonyFoodRationTask`              | ✅ Работает |
| 4 | TNT License | ТНТ имеет шанс удачи для руд             | `UpgradesListener.onTntExplode()`   | ✅ Работает |

**Config секция:** `upgrades.outpost.*`, `upgrades.foodRation.*`, `upgrades.tntLicense.*`

---

## Bank (Банковская зона) - 6 апгрейдов

| № | Название           | Описание                            | Обработчик                              | Статус      |
|---|--------------------|-------------------------------------|-----------------------------------------|-------------|
| 1 | Bank Zone          | Открывает банковскую зону           | ZoneManager                             | ✅ Работает  |
| 2 | ATM                | Возможность покупки доп. банкоматов | SignManager (квота)                     | ✅ Работает  |
| 3 | Safe Deposit Boxes | Личные сейфы-сундуки                | `BankUpgradesManager.onSafeOpen()`      | ✅ Работает  |
| 4 | ATM Network        | Банкоматы с комиссиями              | `BankUpgradesManager.calculateAtmFee()` | ✅ API готов |
| 5 | Free Transfer      | Переводы граждан без комиссии       | `BankUpgradesManager.calculateAtmFee()` | ✅ API готов |
| 6 | Deposit Interest   | Проценты на вклад (ежечасно)        | `BankUpgradesManager` таск              | ✅ Работает  |

**Config секция:** `upgrades.bank.*`
**Менеджер:** `BankUpgradesManager.java`

---

## Park (Парковая зона) - 5 апгрейдов

| № | Название            | Описание                     | Обработчик                          | Статус     |
|---|---------------------|------------------------------|-------------------------------------|------------|
| 1 | Park Zone           | Открывает парковую зону      | ZoneManager                         | ✅ Работает |
| 2 | Gardener's Hut      | Ускорение роста растений 15% | `ParkUpgradesManager.onPlantGrow()` | ✅ Работает |
| 3 | Quiet Guard         | Глушение громких звуков      | `ParkUpgradesManager.onGameEvent()` | ✅ Работает |
| 4 | Pond and Flowerbeds | Восстановление сатурации     | `ParkUpgradesManager` таск          | ✅ Работает |
| 5 | Quiet Hour          | Нет спавна монстров ночью    | `ParkUpgradesManager.onMobSpawn()`  | ✅ Работает |

**Config секция:** `upgrades.park.*`
**Менеджер:** `ParkUpgradesManager.java`

---

## Hospital (Госпитальная зона) - 7 апгрейдов

| № | Название      | Описание                     | Обработчик                                   | Статус     |
|---|---------------|------------------------------|----------------------------------------------|------------|
| 1 | Hospital Zone | Открывает госпитальную зону  | ZoneManager                                  | ✅ Работает |
| 2 | Psych Support | Luck I при смерти            | `HospitalUpgradesManager.onPlayerDeath()`    | ✅ Работает |
| 3 | Diet          | +1 сатурация при еде в зоне  | `HospitalUpgradesManager.onPlayerEat()`      | ✅ Работает |
| 4 | Regen Pulse   | Regeneration I каждые 30 сек | `HospitalUpgradesManager` таск               | ✅ Работает |
| 5 | Sanitary Zone | -30% спавна мобов в радиусе  | `HospitalUpgradesManager.onMobSpawn()`       | ✅ Работает |
| 6 | Blood Gift    | +1 сердце на 5 мин после сна | `HospitalUpgradesManager.onPlayerLeaveBed()` | ✅ Работает |
| 7 | Triage        | -40% длительности дебаффов   | `HospitalUpgradesManager.onPotionEffect()`   | ✅ Работает |

**Config секция:** `upgrades.hospital.*`
**Менеджер:** `HospitalUpgradesManager.java`

---

## Library (Библиотечная зона) - 3 апгрейда

| № | Название             | Описание                             | Обработчик                                     | Статус      |
|---|----------------------|--------------------------------------|------------------------------------------------|-------------|
| 1 | Library Zone         | Открывает библиотечную зону          | ZoneManager                                    | ✅ Работает  |
| 2 | Scrolls of Economy   | -1 лапис, -10% опыта при зачаровании | `LibraryUpgradesManager.onEnchant()`           | ✅ Работает  |
| 3 | Calm                 | Голод тратится медленнее (-10%)      | `LibraryUpgradesManager.onFoodLevelChange()`   | ✅ Работает  |
| 4 | Education Initiative | Квесты дают +10% награды             | `LibraryUpgradesManager.applyEducationBonus()` | ✅ API готов |

**Config секция:** `upgrades.library.*`
**Менеджер:** `LibraryUpgradesManager.java`

---

## State/Country (Государственные апгрейды) - 12 апгрейдов

| №  | Название         | Описание                                  | Обработчик                                               | Статус      |
|----|------------------|-------------------------------------------|----------------------------------------------------------|-------------|
| 1  | State Contracts  | Гос.заказы с наградой                     | Команда `/state contract`                                | ✅ Работает  |
| 2  | Luxury Tax       | Налог на роскошь (5%)                     | `StateUpgradesManager.calculateLuxuryTax()`              | ✅ API готов |
| 3  | Toll Roads       | Пошлина при пересечении границ            | `StateUpgradesManager.onPlayerMove()`                    | ✅ Работает  |
| 4  | Export Rebate    | Возврат налога за экспорт (3%)            | `StateUpgradesManager.calculateExportRebate()`           | ✅ API готов |
| 5  | Resource Focus   | +8% к ресурсу, -4% к остальным            | `StateUpgradesManager.onBlockBreak()` + `onPlayerFish()` | ✅ Работает  |
| 6  | Party Propaganda | Объявления в чат каждые 30 мин            | `StateUpgradesManager` таск                              | ✅ Работает  |
| 7  | Censorship       | Фильтрация чата/табличек                  | `StateUpgradesManager.onPlayerChat()`                    | ✅ Работает  |
| 8  | Curfew           | Slowness+Darkness ночью                   | `StateUpgradesManager` таск                              | ✅ Работает  |
| 9  | Repair Guild     | Переработка инструментов → 50% материалов | `StateUpgradesManager.onPlayerBreakTool()`               | ✅ Работает  |
| 10 | Trading Zone     | +5 слотов для магазинов                   | `StateUpgradesManager.getExtraShopSlots()`               | ✅ API готов |
| 11 | Sampler          | Образцы товаров (кулдаун 2ч)              | `StateUpgradesManager.onSampleItemClick()`               | ✅ Работает  |
| 12 | Happy Hour       | Скидки в определённые часы                | `StateUpgradesManager.getHappyHourDiscount()`            | ✅ API готов |

**Config секция:** `upgrades.state.*`
**Менеджер:** `StateUpgradesManager.java`
**Команды:** `/state contract|focus|sampler|happyhour|recycle`

---

## Church (Церковная зона) - 6 апгрейдов ⚠️ НЕ РЕАЛИЗОВАНЫ

| № | Название           | Описание                      | Планируется                              | Статус      |
|---|--------------------|-------------------------------|------------------------------------------|-------------|
| 1 | Church Zone        | Открывает церковную зону      | ZoneManager                              | ⚠️ TODO     |
| 2 | Pilgrimage         | Бафф при посещении церкви     | `ChurchUpgradesManager`                  | ⚠️ TODO     |
| 3 | Pilgrim Protection | -5% входящего урона гражданам | `ChurchUpgradesManager.onEntityDamage()` | ⚠️ TODO     |
| 4 | Festival of Lights | Ивент с Luck I + редкий дроп  | `ChurchUpgradesManager` таск             | ⚠️ TODO     |
| 5 | Spark Return       | Сохранение XP при смерти      | `ChurchUpgradesManager.onPlayerDeath()`  | ⚠️ TODO     |
| 6 | Night's Rest       | Отключение фантомов           | `UpgradesListener` (частично)            | ⚠️ Частично |
| 7 | Holy Aura          | Нежить горит в зоне           | `ChurchUpgradesManager`                  | ⚠️ TODO     |

---

## Сводная статистика

| Категория  | Всего апгрейдов | Реализовано     | API готов | TODO     |
|------------|-----------------|-----------------|-----------|----------|
| Industrial | 15              | ✅ 15            | -         | -        |
| Fields     | 7               | ✅ 7             | -         | -        |
| Colony     | 4               | ✅ 4             | -         | -        |
| Bank       | 6               | ✅ 6             | -         | -        |
| Park       | 5               | ✅ 5             | -         | -        |
| Hospital   | 7               | ✅ 7             | -         | -        |
| Library    | 4               | ✅ 4             | -         | -        |
| State      | 12              | ✅ 9 (события)   | ✅ 3 (API) | -        |
| Church     | 7               | ⚠️ 1 (частично) | -         | ⚠️ 6     |
| **ИТОГО**  | **67**          | **✅ 58**        | **✅ 3**   | **⚠️ 6** |

## Как использовать

### Автоматические апгрейды
Большинство апгрейдов работают автоматически через обработчики событий. Просто выдайте нужный permission стране/игроку.

### API-методы для интеграции
Некоторые апгрейды предоставляют API для интеграции с другими системами (торговля, квесты):

```java
UnityLauncher plugin = UnityLauncher.getInstance();

// State
StateUpgradesManager state = plugin.getStateUpgradesManager();
double tax = state.calculateLuxuryTax(country, item, price);

// Bank
BankUpgradesManager bank = plugin.getBankUpgradesManager();
double fee = bank.calculateAtmFee(player, location, amount);

// Library
LibraryUpgradesManager library = plugin.getLibraryUpgradesManager();
double reward = library.applyEducationBonus(player, baseReward);
```

### Команды
- `/brand` - маркировка предметов
- `/state <subcommand>` - управление государственными апгрейдами

### Конфигурация
Все настройки в `upgrades.yml` (файл плагина UnityLauncher).  
Структура ключей: `core.*`, `commands.*`, `zones.*`, `industrial.*`, `fields.*`, `colony.*`, `bank.*`, `park.*`, `hospital.*`, `library.*`, `state.*`, `church.*`.

### Отладка
`upgrades.debug: true` в config.yml для подробных логов.
