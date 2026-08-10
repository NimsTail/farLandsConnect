# 🎮 FarLandsConnect - UnityLauncher Plugin

> Полнофункциональная система управления многострановой экономикой и региональным развитием для Minecraft Paper сервера.

![Version](https://img.shields.io/badge/version-1.1-blue.svg)
![Java](https://img.shields.io/badge/java-21+-orange.svg)
![License](https://img.shields.io/badge/license-Private-red.svg)
![Status](https://img.shields.io/badge/status-Active%20Development-green.svg)

---

## 📋 Содержание

- [Обзор](#-обзор)
- [Основные возможности](#-основные-возможности)
- [Архитектура](#-архитектура)
- [Компоненты системы](#-компоненты-системы)
- [Система апгрейдов](#-система-апгрейдов-67-штук)
- [Установка и сборка](#-установка-и-сборка)
- [Конфигурация](#-конфигурация)
- [Команды](#-команды)
- [Интеграция](#-интеграция)
- [Разработка](#-разработка)

---

## 🎯 Обзор

**UnityLauncher** — это комплексный Minecraft плагин для сервера **FarLandsConnect**, который интегрируется с внешним Unity-лаунчером и предоставляет:

- 🏙️ **Систему управления странами и зонами** с регионами и границами
- 💰 **Финансовую экосистему** с биллингом зон и системой счетов
- 🔐 **Авторизацию** с PBKDF2 хешированием паролей
- ⚡ **67 апгрейдов** в 9 категориях для развития станций
- 🗺️ **Интеграцию с BlueMap** (тепловая карта, маркеры)
- 🏪 **Систему магазинов** (Buy/Sell/Exchange/Warp таблички)
- 🌐 **WebSocket мост** для связи с Unity-лаунчером
- 📊 **Отслеживание активности** по чанкам с расчетом биллинга

---

## ✨ Основные возможности

### 🏛️ Управление странами и зонами
- Создание зон с многоугольной формой (JTS geometry)
- Типы зон: Industrial, Fields, Colony, Bank, Park, Hospital, Library, Church
- Система границ между странами
- Отслеживание принадлежности зон странам

### 💳 Финансовая система
- Баланс игроков (Vault интеграция)
- Система счетов (invoices) с дедлайнами
- Автоматический ежедневный/еженедельный биллинг зон
- Расчет стоимости на основе активности в чанках

### 👥 Авторизация
- PBKDF2-HMAC-SHA256 хеширование (120K итераций)
- TTL сессии: 24 часа
- Команды: `/register`, `/login`, `/ul change`

### 🏪 Магазины
- 4 типа табличек: Buy/Sell/Exchange/Warp
- Автоматическая проверка инвентаря и денег
- Интеграция с апгрейдами (налоги, скидки, лимиты)
- Система образцов товаров

### ⚡ 67 Апгрейдов в 9 категориях
- **Industrial** (15): редстоун, воронки, печи, майнер, маяк
- **Fields** (7): элитная еда, размножение, гидропоника
- **Colony** (4): форпост, продпай, ТНТ-лицензия
- **Bank** (6): сейфы, банкоматы, проценты
- **Park** (5): садовод, тишина, восстановление
- **Hospital** (7): лечение, диета, регенерация
- **Library** (4): зачарование, образование
- **State** (12): контракты, налоги, пошлины, пропаганда
- **Church** (6): в разработке

---

## 🏗️ Архитектура

```
┌─────────────────────────────────────────────────────────┐
│                    UnityLauncher (Main)                 │
│                     (Синглтон Plugin)                   │
└──────────┬──────────────────────────────────────────────┘
           │
    ┌──────┴──────────────────────────────────────────┐
    │                                                  │
    ▼                                                  ▼
┌─────────────┐                          ┌──────────────────┐
│  ZoneManager │◄──────────────────────► │  SignManager     │
│ (Зоны/Страны)                         │ (Магазины)       │
└─────────────┘                          └──────────────────┘
    │                                           │
    │     ┌─────────────────────────┐          │
    └────►│  UpgradesManager        │◄─────────┘
          │  (67 апгрейдов)        │
          └────┬────────────────────┘
               │
      ┌────────┼────────┐
      ▼        ▼        ▼
 [Listeners] [Tasks] [API Methods]
```

### Компоненты инициализации

```
onEnable():
  1. Загрузка конфигов (secrets, db.properties)
  2. Инициализация HikariCP пула БД (MySQL)
  3. Регистрация основных листенеров
  4. Создание менеджеров (MoneyManager, ActivityTracker, ZoneManager)
  5. Создание UpgradesManager (главный)
  6. Запуск периодических тасков (биллинг, активность)
  7. Запуск WebSocket моста
```

---

## 🔧 Компоненты системы

### 1. **ZoneManager** - Управление зонами и странами
**Файл:** `zones/ZoneManager.java`

**Что делает:**
- Создание/удаление/изменение зон
- Хранение зон в MySQL
- Проверка "точка в многоугольнике" (JTS)
- Отправка маркеров на BlueMap
- Управление границами между странами

**Пример использования:**
```java
ZoneManager zm = plugin.getZoneManager();
Zone zone = zm.getZoneAt(player.getLocation());
if (zone != null) {
    player.sendMessage("Вы в зоне: " + zone.getName());
}
```

---

### 2. **SignManager** - Система магазинов
**Файл:** `signs/SignManager.java`

**Что делает:**
- Создание табличек Buy/Sell/Exchange/Warp
- Обработка клика по табличке
- Проверка инвентаря и денег
- Интеграция с апгрейдами (налоги, скидки)

**Формат таблички (4 строки):**
```
[Buy] или [Sell] или [Exchange]
<название товара>
<цена>
<количество>
```

---

### 3. **MoneyManager** - Финансовая система
**Файл:** `MoneyManager.java`

**Что делает:**
- Управление балансом игроков (Vault)
- Отслеживание транзакций
- Интеграция с системой счетов

**API:**
```java
MoneyManager mm = plugin.getMoneyManager();
double balance = mm.getBalance(player);
mm.addBalance(player, 100.0);
```

---

### 4. **ActivityTracker** - Отслеживание активности
**Файл:** `chunkactivity/ActivityTracker.java`

**Что делает:**
- Подсчет активности по чанкам (блоки, рыбалка и т.д.)
- Сохранение в БД раз в минуту
- Предоставление данных для биллинга

**Как работает:**
```
1. Слушает события Bukkit (BreakBlock, PlaceBlock и т.д.)
2. Увеличивает счётчик активности в ConcurrentHashMap
3. Раз в минуту усредняет и пишет в БД
4. ZoneActivityCalculations берёт данные для биллинга
```

---

### 5. **AuthService** - Авторизация и регистрация
**Файл:** `auth/AuthService.java`

**Что делает:**
- Хеширование паролей PBKDF2
- Управление сессиями (24h TTL)
- Проверка логина/регистрации

**Параметры PBKDF2:**
- Алгоритм: HMAC-SHA256
- Итерации: 120,000
- Длина ключа: 256 бит

**Команды:**
```
/register <пароль>           — зарегистрироваться
/login <пароль>              — залогиниться
/ul change <старый> <новый>  — смена пароля
```

---

### 6. **BlueMapIntegration** - Интеграция с картой
**Файл:** `BlueMapIntegration.java`

**Что делает:**
- Отправка маркеров зон на карту
- Тепловая карта активности (BlueMapHeatService)
- Обновление маркеров при изменении зон

---

### 7. **UpgradesManager** - Главный менеджер апгрейдов
**Файл:** `upgrades/core/UpgradesManager.java`

**Архитектура:**
```
UpgradesManager
├── register(Upgrade)        — регистрация апгрейда
├── reload()                 — перезагрузка всех
├── getEnabled(Class<T>)     — получить включённый апгрейд
└── [Upgrade реализации]
    ├── enable()  → регистрация листенеров
    ├── disable() → удаление листенеров
    └── обработка событий
```

**Жизненный цикл апгрейда:**
```
1. Конструирование менеджером
2. isEnabled() проверяет пермиссию в LuckPerms
3. enable() регистрирует @EventHandler методы
4. Обработка событий Bukkit
5. disable() удаляет листенеры при перезагрузке
```

---

## ⚡ Система апгрейдов (67 штук)

### Распределение по файлам

| Файл | Апгрейды | Кол-во | Статус |
|------|----------|--------|--------|
| `UpgradesListener.java` | Industrial, Fields, Colony | 26 | ✅ Работает |
| `BankUpgradesManager.java` | Bank (Safe, ATM, Interest) | 6 | ✅ Работает |
| `ParkUpgradesManager.java` | Park (Gardener, Quiet, Pond) | 5 | ✅ Работает |
| `HospitalUpgradesManager.java` | Hospital (Psych, Diet, Regen) | 7 | ✅ Работает |
| `LibraryUpgradesManager.java` | Library (Scrolls, Calm, Education) | 4 | ✅ Работает |
| `StateUpgradesManager.java` | State (Contracts, Taxes, Focus) | 12 | ✅ Работает |
| `ChurchUpgrade.java` | Church (Pilgrimage, Protection) | 7 | ⚠️ TODO |

### Типы апгрейдов

#### 🟢 Автоматические (58 апгрейдов)
Срабатывают через обработчики событий Bukkit. Сразу после выдачи пермиссии:

```java
@EventHandler
public void onBlockPlace(BlockPlaceEvent e) {
    String country = UpgradeCondition.playerCountryCanonical(e.getPlayer().getName());
    int level = countryMaxLevel(country, config.redstonePerm, 2);
    
    if (level < 1) {
        e.setCancelled(true); // Блокируем без апгрейда
    }
}
```

**Примеры:**
- Редстоун гейтинг (блокировка/разрешение блоков)
- Ускорение воронок (Smart Hoppers)
- Бонусы при ловле рыбы (Resource Focus)
- Эффекты в зоне (Haste, Luck, Regen и т.д.)

#### 🔵 API методы (9 апгрейдов)
Требуют вызова методов из других систем:

```java
// При продаже товара
StateUpgradesManager state = plugin.getStateUpgradesManager();
double tax = state.calculateLuxuryTax(country, item, price);

// При покупке
int discount = state.getHappyHourDiscount(shopLocation);

// При выдаче награды за квест
LibraryUpgradesManager lib = plugin.getUpgradesManager()
    .getEnabled(LibraryUpgradesManager.class);
double bonus = lib.applyEducationBonus(player, baseReward);
```

#### 🟡 Команды (3 апгрейда)
Интерактивные команды для игроков:

```
/brand set <текст>          — маркировать предметы
/state contract create ...  — создать контракт
/state focus <resource>     — установить фокус на ресурс
```

### Статистика

| Метрика | Значение |
|---------|----------|
| **Всего апгрейдов** | 67 |
| **Готовых и работающих** | 58 (87%) |
| **API для интеграции** | 9 (13%) |
| **В разработке (Church)** | 6 (9%) |

---

## 💾 База данных

### Главные таблицы

```sql
-- Авторизация
auth_users (id, username, password_hash, salt, created_at)

-- Страны
countries (id, name, leader_uuid, founded_at, ...)

-- Зоны
zones (id, country_id, name, type, polygon_wkt, active, ...)

-- Счета в банке
bank_invoices (id, country_id, amount, reason, due_date, paid, ...)

-- Активность по чанкам
chunk_activity (chunk_x, chunk_z, world_name, activity_value, updated_at)

-- Магазины/таблички
signs (id, x, y, z, world, type, items, ...)

-- Транзакции в магазинах
transactions (id, player, sign_id, amount, item_type, ...)

-- Логи активности
activity_logs (id, chunk_x, chunk_z, event_type, amount, ...)
```

---

## 📥 Установка и сборка

### Требования

- **Java:** 21+
- **Maven:** 3.8+
- **MySQL:** 8.0+
- **Minecraft Server:** Paper 1.21.10+

### Сборка проекта

```bash
# Клонировать репозиторий
git clone https://github.com/your-org/farLandsConnect.git
cd farLandsConnect

# Собрать jar
mvn clean package

# JAR будет в target/unityLauncher-1.1.jar
```

### Установка на сервер

1. Скопировать `target/unityLauncher-1.1.jar` в папку `plugins/`
2. Убедиться, что установлены все зависимости (см. ниже)
3. Отредактировать конфиги (см. раздел Конфигурация)
4. Перезагрузить сервер: `/reload confirm`

### Зависимости (обязательные плагины)

| Плагин | Версия | Для чего |
|--------|--------|---------|
| **LuckPerms** | 5.4+ | Система прав (пермиссии для апгрейдов) |
| **UltimateAdvancementAPI** | 2.4.0+ | Система достижений |
| **ProtocolLib** | 5.4.0+ | Манипуляция паketами |
| **BlueMap** | 2.7.2+ | Отображение карты и маркеров |

**Опциональные плагины:**
- **Vault** (эконом-интеграция)
- **TAB** (кастомизация табле­ницы)
- **PlaceholderAPI** (переменные типа %unity_prefix%)

---

## ⚙️ Конфигурация

### Главный конфиг: `config.yml`

```yaml
# БД
database:
  host: localhost
  port: 3306
  name: farlands
  user: minecraft
  password: "***"

# Апгрейды
upgrades:
  debug: false           # подробное логирование
  auto-reload: true      # автоперезагрузка при изменении

# BlueMap
bluemap:
  enabled: true
  update-interval: 300   # сек

# Авторизация
auth:
  ttl-hours: 24
  max-login-attempts: 5
```

### Конфиг апгрейдов: `upgrades.yml`

```yaml
upgrades:
  core:
    debug: false
  
  industrial:
    redstone:
      description: "Открывает редстоун (repeater, torch, ...)"
      perm: "unity.upgrade.redstone.1"
      enabled: true
    
    hoppers:
      description: "Ускорение воронок на 25%"
      perm: "unity.upgrade.hoppers"
      enabled: true
  
  bank:
    safe:
      description: "Личные сейфы-сундуки"
      perm: "unity.bank.safe"
      enabled: true
    
    atm:
      description: "Банкоматы с комиссией"
      perm: "unity.bank.atm"
      fee-percent: 2.5
      enabled: true
```

### Конфиг дорожных пошлин: `toll_roads.yml`

```yaml
tolls:
  world_name:
    - name: "Border_Russia_USA"
      from-country: "Russia"
      to-country: "USA"
      amount: 50.0
      checkpoint-x: 1000
      checkpoint-z: 500
```

---

## 🎮 Команды

### 🔐 Авторизация

```
/register <пароль>              Зарегистрировать аккаунт
/login <пароль>                 Залогиниться
/ul change <старый> <новый>     Смена пароля
```

### 💰 Финансы

```
/ul balance                      Показать личный баланс
/ul zone price                   Стоимость текущей зоны
```

### 🗺️ Зоны

```
/ul zone addcorner <тип>         Добавить точку контура
/ul zone build <тип> [имя]       Построить зону
/ul zone update corners +/-      Расширить/сузить границы
/ul zone update name <имя>       Переименовать зону
/ul zone update color R,G,B      Изменить цвет на карте
/ul zone remove                  Запросить удаление зоны
/ul zone confirmremove           Подтвердить удаление
/ul zone cancelremove            Отменить удаление
```

### 💎 Апгрейды

```
/brand set <текст>               Маркировать предметы
/brand clear                      Удалить маркировку
/brand info                       Инфо о предмете

/state contract create ...       Создать государственный контракт
/state contract list             Список контрактов

/state focus <fish|wood|ore>     Установить ресурсный фокус
/state sampler                   Создать образец товара
/state happyhour <h1> <h2> <d>  Установить happy hour
/state recycle                   Переработать инструмент
```

### 👨‍💼 Админ

```
/ul reload                       Перезагрузить конфиги и апгрейды
/ul expo                         Экспортировать тепловую карту
/ul fsnap                        Принудительный подсчёт зон (биллинг)
/ul blist                        Очередь биллинга зон
/ul fpslink <url>               Отправить ссылку в лаунчер
```

---

## 🔗 Интеграция

### Мост с сайтом farlandsconnect (`auth/FarLandsApiClient.java`)

Плагин — единственный источник правды для всей игровой экономики/зон/апгрейдов
(своя MySQL). Сайт (`farlands.in`) — отдельная система с собственной Postgres,
которой для входа игроков и части UI (баланс, задания, last-seen) нужна
**копия**, а не прямой доступ к MySQL. `FarLandsApiClient` — fire-and-forget
HTTP-клиент (Java 21 `HttpClient`, без внешних зависимостей), который после
локальной мутации в MySQL дублирует событие на бэкенд сайта через `/plugin/*`
(контракт — `infra/auth-api-contract.md` в репозитории `farlandsconnect`).

Важно: это **зеркало, не источник истины**. Если бэкенд сайта недоступен —
ошибка логируется (`[FarLandsApi] ... failed: ...`) и локальная логика
(регистрация/логин/экономика) работает как ни в чём не бывало.

Сейчас замирроренa только авторизация:

| Локальное событие | Хук | Эндпоинт сайта |
|---|---|---|
| `AuthService.registerNewUser()` | после успешной вставки в `Users` | `POST /plugin/users` |
| `AuthService.setNewPassword()` | после успешного `UPDATE Users SET Password` | `POST /plugin/users/:username/password` |
| `AuthListener.onQuit()` (если игрок был аутентифицирован) | при выходе | `POST /plugin/users/:username/last-seen` |

Конфиг — `plugins/UnityLauncher/secrets.properties` (не в git, runtime-файл,
не бейкается в jar — специально, чтобы один и тот же jar работал и на dev,
и на проде с разными значениями):
```properties
backend.apiBaseUrl=https://farlands.frammy.lat
backend.apiToken=<PLUGIN_API_TOKEN из backend/.env на VPS>
```
Пустые значения — мост выключен, `FarLandsApiClient.isEnabled() == false`.

### Интеграция с системой магазинов (SignManager)

```java
// При обработке продажи товара
StateUpgradesManager state = plugin.getStateUpgradesManager();

// Расчет налога на роскошь
double tax = state.calculateLuxuryTax(country, item, price);

// Возврат при экспорте
double rebate = state.calculateExportRebate(seller, location, price);

// Happy Hour скидка
int discount = state.getHappyHourDiscount(shopLocation);
finalPrice = price * (1.0 - discount / 100.0);
```

### Интеграция с системой квестов

```java
// При выдаче награды за квест
LibraryUpgradesManager lib = plugin.getUpgradesManager()
    .getEnabled(LibraryUpgradesManager.class);

double baseReward = 100.0;
double finalReward = lib.applyEducationBonus(player, baseReward);
```

### Интеграция с банкоматами

```java
// При снятии с банкомата
BankUpgradesManager bank = plugin.getBankUpgradesManager();
double fee = bank.calculateAtmFee(player, atmLocation, withdrawAmount);
double amountAfterFee = withdrawAmount - fee;
```

### Проверка условий апгрейда

```java
String country = UpgradeCondition.playerCountryCanonical(playerName);
int level = UpgradeCondition.countryMaxLevel(country, permissionPref, maxLevel);

if (level >= 1) {
    // Апгрейд активен для этой страны
}
```

---

## 🛠️ Разработка

### Структура проекта

```
src/main/
├── java/com/frammy/unitylauncher/
│   ├── UnityLauncher.java              # Главный класс (синглтон)
│   │
│   ├── zones/
│   │   ├── ZoneManager.java            # Менеджер зон
│   │   ├── Zone.java                   # Класс зоны
│   │   └── ...
│   │
│   ├── signs/
│   │   ├── SignManager.java            # Менеджер магазинов
│   │   └── ...
│   │
│   ├── chunkactivity/
│   │   ├── ActivityTracker.java        # Трекинг активности
│   │   └── ...
│   │
│   ├── auth/
│   │   ├── AuthService.java            # Авторизация
│   │   └── ...
│   │
│   ├── upgrades/
│   │   ├── core/
│   │   │   ├── UpgradesManager.java    # Главный менеджер
│   │   │   └── Upgrade.java            # Интерфейс апгрейда
│   │   │
│   │   ├── impl/
│   │   │   ├── BankUpgradesManager.java
│   │   │   ├── ParkUpgradesManager.java
│   │   │   ├── HospitalUpgradesManager.java
│   │   │   ├── LibraryUpgradesManager.java
│   │   │   ├── StateUpgradesManager.java
│   │   │   └── ... (ещё 30+ файлов)
│   │   │
│   │   └── config/
│   │       ├── UpgradesConfig.java    # Интерфейс конфига
│   │       └── UpgradesCfg.java       # Реализация
│   │
│   └── ... (другие компоненты)
│
└── resources/
    ├── plugin.yml                      # Манифест плагина
    ├── config.yml                      # Главный конфиг
    ├── upgrades.yml                    # Конфиг апгрейдов
    └── toll_roads.yml                  # Пошлины
```

### Как добавить новый апгрейд

#### 1. Добавить конфиг в `UpgradesConfig.java`

```java
// В интерфейсе UpgradesConfig
public interface UpgradesConfig {
    String myNewUpgradePerm();
    int myNewUpgradeValue();
    // ...
}

// В реализации UpgradesCfg
public final String myNewUpgradePerm;
public final int myNewUpgradeValue;

public UpgradesCfg(FileConfiguration cfg) {
    myNewUpgradePerm = cfg.getString("state.myNewUpgrade.perm", "unity.my.upgrade");
    myNewUpgradeValue = cfg.getInt("state.myNewUpgrade.value", 10);
}

public static void addDefaults(FileConfiguration c) {
    c.addDefault("state.myNewUpgrade.description", "Описание апгрейда");
    c.addDefault("state.myNewUpgrade.perm", "unity.my.upgrade");
    c.addDefault("state.myNewUpgrade.value", 10);
}
```

#### 2. Создать класс апгрейда

```java
public class MyNewUpgrade extends Upgrade {
    
    @Override
    public UpgradeKey key() {
        return new UpgradeKey("state", "myNewUpgrade");
    }
    
    @Override
    public boolean isEnabled(UpgradesCfg cfg) {
        return true; // или логика для включения
    }
    
    @Override
    public void enable(UpgradeContext ctx) {
        // Регистрация листенеров
        Bukkit.getPluginManager().registerEvents(this, ctx.plugin());
    }
    
    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
    }
    
    @EventHandler
    public void onSomeEvent(SomeEvent e) {
        String country = UpgradeCondition.playerCountryCanonical(e.getPlayer().getName());
        int level = UpgradeCondition.countryMaxLevel(country, ctx.config().myNewUpgradePerm(), 1);
        
        if (level < 1) return;
        
        // Логика апгрейда
    }
}
```

#### 3. Зарегистрировать апгрейд в `UpgradesManager`

```java
upgradesManager.register(new MyNewUpgrade());
```

#### 4. Добавить в конфиг `upgrades.yml`

```yaml
upgrades:
  state:
    myNewUpgrade:
      description: "Описание апгрейда"
      perm: "unity.my.upgrade"
      enabled: true
```

#### 5. Документировать в `UPGRADES_CATALOG.md`

---

### Система логирования

```java
// Включить debug mode
// В config.yml: upgrades.debug: true

// Логирование в апгрейдах
if (cfg.debug) {
    Bukkit.getLogger().info("[UL/MyUpgrade] Event triggered: " + e);
}

// Логи в консоли:
// [UL/UpgradesListener] onBlockPlace: Redstone blocked
// [UL/ZoneManager] Zone loaded from DB
```

---

## 📊 Производительность и оптимизация

✅ **Что оптимизировано:**

- **ConcurrentHashMap** для всех кэшей (потокобезопасность)
- **Асинхронные таски** для операций с БД (Bukkit.getScheduler().runTaskAsynchronously)
- **Ленивая загрузка BlueMap** (только при надобности через LazyBlueMapLoader)
- **Кэширование прав LuckPerms** в UpgradeCondition
- **HikariCP пул** для БД (до 10 соединений)
- **Таски запущены асинхронно** где возможно

⚡ **Тики:**

- Апгрейды срабатывают на событиях Bukkit (0 тиков)
- Воронки обновляются каждые 5 тиков (1/4 секунды)
- Биллинг зон запускается 1 раз в день (асинхронно)
- Активность записывается в БД раз в минуту

---

## 🐛 Отладка

### Включить подробные логи

```yaml
# В config.yml
upgrades:
  debug: true
```

### Команды отладки

```bash
# Выдать апгрейд стране (тест)
/lp group country_test permission set unity.upgrade.redstone.1 true

# Проверить логи
grep "UL/" server.log | tail -50

# Перезагрузить конфиги
/ul reload
```

### Проверка работоспособности

```java
// Проверить, включен ли апгрейд
String country = UpgradeCondition.playerCountryCanonical("TestPlayer");
int level = UpgradeCondition.countryMaxLevel(country, "unity.upgrade.redstone.1", 1);
Bukkit.getLogger().info("Redstone level: " + level);
```

---

## 📚 Дополнительная документация

- **[UPGRADES_CATALOG.md](UPGRADES_CATALOG.md)** — полная таблица всех 67 апгрейдов с описаниями
- **[UPGRADES_INTEGRATION.md](UPGRADES_INTEGRATION.md)** — руководство по интеграции API апгрейдов
- **[UPGRADES_SUMMARY.md](UPGRADES_SUMMARY.md)** — краткая сводка по апгрейдам

---

## 📈 Дорожная карта

### ✅ Завершено (v1.1)
- [x] Система зон и границ
- [x] Финансовая система (биллинг)
- [x] Авторизация (PBKDF2)
- [x] Система магазинов
- [x] 58 готовых апгрейдов
- [x] BlueMap интеграция
- [x] WebSocket мост

### 🔄 В разработке
- [ ] Church апгрейды (6)
- [ ] Система дипломатии (международные отношения)
- [ ] Расширенная система квестов

### 📋 Планируется
- [ ] Система гильдий
- [ ] Расширенная торговля между странами
- [ ] Система войн и завоеваний
- [ ] Пользовательский интерфейс в лаунчере

---

## 🤝 Контрибьютинг

Для разработчиков, работающих над проектом:

1. Создайте ветку: `git checkout -b feature/my-feature`
2. Коммитьте изменения: `git commit -am 'Add my feature'`
3. Пушьте в ветку: `git push origin feature/my-feature`
4. Откройте Pull Request

### Код стиль
- Java 21+
- Используйте конкатенацию через `String#format()` или `StringBuilder`
- Комментируйте сложную логику
- Следуйте существующему стилю кода

---

## 📝 Лицензия

Это приватный проект для сервера **FarLandsConnect**.

**Авторы:**
- frammy (главный разработчик)
- NimsTail (соразработчик)

**© 2024-2026 FarLandsConnect. Все права защищены.**

---

## 📞 Контакты

- **Вебсайт:** farlands.in
- **Discord:** (ссылка тут)
- **GitHub Issues:** для репортов багов и фич-реквестов

---

## 🎓 Примеры кода

### Пример 1: Проверка апгрейда страны

```java
UnityLauncher plugin = UnityLauncher.getInstance();
String country = UpgradeCondition.playerCountryCanonical(player.getName());
int redstoneLvl = UpgradeCondition.countryMaxLevel(country, "unity.upgrade.redstone.1", 2);

if (redstoneLvl >= 1) {
    player.sendMessage("§aРедстоун разрешён!");
} else {
    player.sendMessage("§cНужен апгрейд Industrial: Basic Redstone");
}
```

### Пример 2: Получение менеджера апгрейдов

```java
UnityLauncher plugin = UnityLauncher.getInstance();

// Получить конкретный менеджер
StateUpgradesManager state = plugin.getUpgradesManager()
    .getEnabled(StateUpgradesManager.class);

if (state != null) {
    double tax = state.calculateLuxuryTax(country, item, price);
}
```

### Пример 3: Работа с зонами

```java
ZoneManager zm = plugin.getZoneManager();
Zone zone = zm.getZoneAt(player.getLocation());

if (zone != null && zone.getType().equals("bank")) {
    player.sendMessage("§aВы в банковской зоне: " + zone.getName());
    
    // Получить страну-владельца
    String owner = zone.getCountryName();
}
```

### Пример 4: Применение бонуса из апгрейда

```java
LibraryUpgradesManager lib = plugin.getUpgradesManager()
    .getEnabled(LibraryUpgradesManager.class);

double baseReward = 100.0;
double finalReward = lib != null ? 
    lib.applyEducationBonus(player, baseReward) : 
    baseReward;

player.sendMessage("§6Награда: " + finalReward);
```

---

**Последнее обновление:** 13 апреля 2026  
**Версия документации:** 1.1

