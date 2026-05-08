# Участь у розробці / Contributing

Цей документ пояснює внутрішню будову проєкту — як він влаштований, чому
прийняті ті чи інші рішення і як додавати нові можливості. Написано
українською, для тих хто тільки знайомиться з кодом.

> Цей репозиторій є прямим Kotlin-нащадком оригінальної Java-реалізації
> [oldengremlin/routing-instances-report](https://github.com/oldengremlin/routing-instances-report).
> Java-версія заморожена; нова функціональність розвивається тут.

> 📖 Цей самий вміст продубльовано в [GitHub Wiki проєкту](https://github.com/oldengremlin/routing-instances-report-k/wiki),
> розкладеному по сторінках за розділами. Якщо змінюєте текст — оновлюйте
> обидва місця, щоб не розходилися.

---

## Зміст

1. [Що робить програма](#що-робить-програма)
2. [Загальна архітектура](#загальна-архітектура)
3. [Фази виконання](#фази-виконання)
4. [Опис класів](#опис-класів)
5. [Ключові патерни та рішення](#ключові-патерни-та-рішення)
6. [Потокова безпека](#потокова-безпека)
7. [Безпека та відомі компроміси](#безпека-та-відомі-компроміси)
8. [Протокол NETCONF](#протокол-netconf)
9. [Як побудувати та запустити локально](#як-побудувати-та-запустити-локально)
10. [Як додати підтримку нового вендора](#як-додати-підтримку-нового-вендора)
11. [Стиль коду](#стиль-коду)

---

## Що робить програма

Мережевий провайдер має десятки маршрутизаторів різних виробників (Juniper,
Cisco, MikroTik). На кожному з них налаштовані сотні VRF, VPLS-контурів,
L2-circuit'ів тощо. Відслідкувати «хто де і в якому стані» вручну — складно.

Програма раз на добу:
1. Підключається до кожного маршрутизатора і зчитує конфігурацію.
2. Об'єднує всі дані в одну структуру — один сервіс, що присутній на кількох
   роутерах, відображається одним рядком.
3. Знаходить «підозрілі» ситуації: L2CIRCUIT/VPLS без парного запису на
   сусідньому кінці, з'єднання у стані down, посилання на інтерфейси, яких
   уже немає у конфігурації пристрою.
4. Генерує HTML-звіт і кладе його у директорію nginx — будь-хто з команди
   відкриває браузер і одразу бачить повну картину.

---

## Загальна архітектура

```
RoutingInstancesReport.main()
        │
        ├─ Фаза 1 ──▶ JuniperCollector × N хостів (паралельно)
        │               └─ NETCONF/SSH → XML → xmlCache + disk dump
        │
        ├─ Фаза 2 ──▶ JuniperSwitchCollector     ┐
        │             JuniperL2circuitCollector    ├─ читають xmlCache (паралельно)
        │             JuniperBridgedomainsCollector┘
        │             CiscoCollector × M хостів   ─ Telnet (паралельно)
        │             RouterOSCollector × K хостів ─ SSH (паралельно)
        │
        ├─ LoAddressMapper.build()   ← читає xmlCache → IP→ім'я словник
        ├─ findOrphans()             ← аналіз пар L2CIRCUIT/VPLS
        │
        ├─ Фаза 3 ──▶ JuniperDownStateCollector × N хостів (паралельно)
        │               └─ NETCONF operational RPC (get-l2ckt + get-vpls з <down/>)
        │
        └─ ReportGenerator.generate() → HTML файл → nginx
```

Всі зібрані дані зберігаються у спільних структурах, побудованих у `main()`:
- `instances: TreeMap<String, RoutingInstance>` — основна таблиця, відсортована
  за ключем дедублікації.
- `vrfVplsList: LinkedHashMap<String, MutableMap<String, String>>` — індекс RD
  (Route Distinguisher) у порядку першого зустрічання.
- `xmlCache: ConcurrentHashMap<String, String>` — спільний кеш сирих XML
  Juniper-конфігурацій.
- `ifaceRegistry: InterfaceRegistry` — лінивий індекс інтерфейсів кожного
  Juniper-роутера (для маркера `(!)`).

---

## Фази виконання

### Чому три фази, а не одна?

**Фаза 1** (тільки `JuniperCollector`) має завершитися перша, бо вона пише
XML-конфігурацію кожного роутера в пам'ять (`xmlCache`) і на диск. Усі інші
Juniper-колектори читають ці дані — якщо запустити їх одночасно з першою
фазою, вони спробують читати ще не записаний кеш.

**Фаза 2** — три дискові Juniper-колектори (Switch/L2circuit/Bridgedomains)
плюс Cisco і RouterOS — між собою незалежні, тому виконуються паралельно.
Дискові колектори не роблять мережевих запитів (читають `xmlCache`) — вони
швидкі і не займають «слоти» семафора.

**Фаза 3** (`JuniperDownStateCollector`) — після `LoAddressMapper.build()`,
бо потребує словник IP→ім'я для перетворення IP-адрес сусідів у читабельні
імена маршрутизаторів у звіті.

### Обмеження паралельності — Semaphore

```kotlin
val maxConcurrent = env("MAX_CONCURRENT", "5").toInt()
val semaphore = Semaphore(maxConcurrent)
```

Семафор обмежує кількість одночасних мережевих з'єднань. Без нього 10+
паралельних SSH-сесій могли б перевантажити роутери або вичерпати ресурси
хоста. Семафор захоплюється перед підключенням і звільняється після (у
`finally`, щоб звільнити навіть при помилці).

### Virtual threads (JVM 21)

```kotlin
Executors.newVirtualThreadPerTaskExecutor().use { executor ->
    for (host in hosts) {
        executor.submit { task(host) }
    }
}   // <- `.use { }` чекає завершення всіх задач
```

Virtual threads — легкі потоки JVM 21, які не блокують OS-потік під час
очікування I/O (SSH/Telnet). Замість 30 секунд на 10 роутерів послідовно —
отримуємо ~30 секунд на всі одночасно (обмежено семафором до 5 паралельних).
Конструкція `executor.use { }` (Kotlin-аналог Java `try-with-resources`)
гарантує, що `main()` рухається далі тільки після того як всі задачі фази
завершені.

---

## Опис класів

### `Collector` (інтерфейс)

Єдиний метод:
```kotlin
@Throws(Exception::class)
fun collect(
    hostname: String,
    instances: MutableMap<String, RoutingInstance>,
    vrfVplsList: MutableMap<String, MutableMap<String, String>>,
)
```

Кожна реалізація: підключається до роутера, парсить відповідь, викликає
`RoutingInstance.merge()` для кожного знайденого сервісу. Помилки кидаються
наверх — `main()` їх ловить, логує і продовжує з наступним хостом.

---

### `AbstractJuniperCollector`

Базовий клас для всіх Juniper-колекторів. Сигнатура:

```kotlin
abstract class AbstractJuniperCollector(
    private val login: String,
    private val pass: String,
    protected val xmlCache: ConcurrentHashMap<String, String>,
    protected val ifaceRegistry: InterfaceRegistry,
) : Collector
```

Містить:

**NETCONF-транспорт:**
- `fetchRpcs(hostname, rpcs)` — відкриває SSH-сесію, обмінюється NETCONF
  hello-повідомленнями, послідовно відсилає кожен RPC і зчитує відповідь,
  закриває сесію. Повертає список відповідей. Ключова деталь: один виклик
  `fetchRpcs` = одна SSH-сесія, скільки б RPC не було у списку.
- `fetchNetconf(hostname)` — зручна обгортка для одного RPC `get-config`
  (отримати running-конфігурацію).
- `readOrFetch(hostname)` — перевіряє `xmlCache` → диск → мережа. Завдяки
  цьому методу три дискові колектори фази 2 не роблять жодного нового
  підключення.

**XML-хелпери:**
- `parseXml(xml)` — парсить рядок у DOM-документ із вимкненим DOCTYPE
  (`disallow-doctype-decl=true`) та `FEATURE_SECURE_PROCESSING=true`.
- `extractRouterName(doc, xp, fallback)` — витягує ім'я роутера з
  `//system/host-name`, відрізає суфікс `-re0`/`-re1` (подвійні RE на
  Juniper), повертає у верхньому регістрі.

**Спільні константи (`companion object`):**
- `DELIM = "]]>]]>"` — роздільник NETCONF 1.0 (RFC 6241).
- `DUMP_DIR` — читається з env `DUMP_DIR`, за замовчуванням `/tmp`.

**Канал SSH** контролюється змінною `OPENCHANNEL`: за замовчуванням
`subsystem-netconf` (стандартний netconf-subsystem); альтернатива — `exec`
(запускає `xml-mode netconf need-trailer` через exec-канал — корисно для
старих JunOS).

**Важлива деталь про `leftover`:** під час читання NETCONF-відповіді з потоку
може прийти більше байт ніж один RPC (наступний RPC вже «прийшов» у буфер).
Ці «зайві» байти зберігаються у `leftover` і використовуються на початку
наступного читання. `leftover` — **локальна змінна** `fetchRpcs()`, а не поле
класу, тому кілька паралельних викликів на одному екземплярі колектора не
заважають одне одному.

---

### `JuniperCollector`

Фаза 1. Отримує повну running-конфігурацію через `get-config`, зберігає у
`xmlCache` і атомарно пише на диск, потім парсить
`//routing-instances/instance[not(ancestor::dynamic-profiles)]`.

**Типи інстансів:**

| XML `instance-type` | Тип у звіті | Умова |
|---------------------|-------------|-------|
| `vrf`               | `VRF`       | завжди |
| `vpls`              | `VPLS/L2`   | немає `<routing-interface>` |
| `vpls`              | `VPLS/L3`   | є `<routing-interface>` (IRB) |

Для VPLS з LDP-сусідами і vpls-id додається **вторинний запис** з ключем
`vpls-id/ROUTER (instance-name)`. Це потрібно щоб у відсортованій таблиці
пов'язані контури стояли поруч (наприклад, `334/R418-1` і `334/R201-1`
будуть поруч незалежно від алфавітного порядку імен інстансів).

**Атомарний запис на диск:**
```kotlin
val tmp = Files.createTempFile(dumpDir, "juniper-$hostname-", ".xml")
Files.writeString(tmp, xmlResponse, StandardCharsets.UTF_8)
Files.move(tmp, dumpFile, StandardCopyOption.ATOMIC_MOVE)
```
Читач (наступний запуск або інший процес) ніколи не побачить файл
наполовину записаним: він або отримає старий повний файл, або новий повний —
завдяки атомарному rename (syscall `rename(2)` на Linux).

**Маркери в host-entry:** `(-)` після імені роутера або інтерфейсу позначає
`inactive="inactive"` у конфігурації. `(!)` після імені інтерфейсу — що в
блоці `<interfaces>` верхнього рівня немає відповідного визначення (див.
`InterfaceRegistry` нижче). Маркери комбінуються вільно: `xe-1/2/0.130(-)(!)`
— посилання неактивне і фізичного інтерфейсу теж немає.

---

### `JuniperSwitchCollector`, `JuniperL2circuitCollector`, `JuniperBridgedomainsCollector`

Фаза 2 (дискові). Всі три викликають `readOrFetch()` — з `xmlCache` без
жодного мережевого з'єднання. Парсять різні XPath у тому самому XML-документі.

| Клас | XPath | Тип |
|------|-------|-----|
| `JuniperSwitchCollector` | `//protocols/connections/interface-switch` | `SWITCH` |
| `JuniperL2circuitCollector` | `//protocols/l2circuit/neighbor/interface` | `L2CIRCUIT` |
| `JuniperBridgedomainsCollector` | `//bridge-domains/domain` | `BRIDGE/L2` або `BRIDGE/L3` |

Усі ігнорують вузли всередині `<dynamic-profiles>` (шаблони, не реальні
інстанси). Усі звертаються до `ifaceRegistry.isMissing(hostname, ifaceName)`
для кожного інтерфейсу, який вписують у host-entry, і додають `(!)` коли
відповідь позитивна.

---

### `JuniperDownStateCollector`

Фаза 3. Не реалізує `collect()` (кидає `UnsupportedOperationException`) —
замість цього має спеціалізований метод
`collectDownState(hostname, loAddresses)`.

Відправляє **два NETCONF operational RPC в одній SSH-сесії:**
```
get-l2ckt-connection-information<down/>   → відповідь 1
get-vpls-connection-information<down/>    → відповідь 2
```

Саме тут `fetchRpcs(hostname, listOf(L2CKT_DOWN_RPC, VPLS_DOWN_RPC))`
показує свою перевагу: два RPC — одна сесія, а не два окремих підключення.

Результат — список `Array<String>` по 6 елементів:
`[тип, роутер, vc-id, instance, сусід, статус]`.

**Фільтр `NO_DOWN_PAT`.** Регулярка `(?i)^No connections found\.?$` відсіює
рядки, у яких `<instance-display-error>` / `<neighbor-display-error>`
дорівнює саме цьому тексту: під фільтром `<down/>` ця відповідь означає
«жодного down-з'єднання не знайдено», тобто інстанс здоровий. Інші
повідомлення (наприклад, `Instance not configured`) проходять як справжні
помилки.

**`resolveRouterName()`** — потрібне ім'я роутера у верхньому регістрі таке
саме, як у головній таблиці. Шукає у `xmlCache` (є після фази 1), якщо немає
— на диску, якщо немає — `hostname.uppercase()`.

---

### `ConnectionStatus` (enum)

`internal enum class ConnectionStatus(val description: String)` — перелік
кодів статусів з відповіді Juniper RPC (`NP`, `OL`, `LD`, `VC_DN`…). Метод
`describe(code)` нормалізує код (заміна `-` на `_`, верхній регістр) і шукає
серед значень enum. Два спеціальні випадки: `"->"` і `"<-"` (тільки один
напрям з'єднання активний) обробляються окремо, бо не є валідними іменами
Kotlin-констант.

---

### `CiscoCollector`

```kotlin
class CiscoCollector(
    private val login: String,
    private val pass: String,
    private val enablePass: String,
) : Collector
```

Підключається через **Telnet** (Apache Commons Net), входить у режим enable,
виконує `terminal length 0` + `show running-config`, парсить блоки
`ip vrf NAME` / `rd X:Y` регулярними виразами. Cisco не підтримує NETCONF у
старих версіях IOS. Сирий конфіг кладеться атомарно у
`$DUMP_DIR/cisco-HOST.conf`.

---

### `RouterOSCollector`

```kotlin
class RouterOSCollector(
    private val login: String,
    private val pass: String,
) : Collector
```

Підключається через **SSH**, виконує `/ip route vrf export compact`, парсить
вивід. Рядки-продовження (backslash + newline у MikroTik) склеюються перед
парсингом. Шукає `route-distinguisher=AS:ID … routing-mark=NAME` і мерджить
як `VRF`.

---

### `RoutingInstance` + `HashUtils`

**`RoutingInstance`** — модель даних одного сервісу. Звичайний Kotlin-клас з
`var`-властивостями (`name`, `type`, `rd`, `hrefname`) і `MutableList<String>`
для `hosts` (по одному рядку на кожен роутер, де присутній цей сервіс).
Lombok тут немає — Kotlin не потребує.

**`merge()` — центральний метод усього збору**, живе у `companion object`:

```kotlin
@JvmStatic
@Synchronized
fun merge(
    instances: MutableMap<String, RoutingInstance>,
    vrfVplsList: MutableMap<String, MutableMap<String, String>>,
    name: String,
    type: String,
    rd: String,
    hostEntry: String,
)
```

1. Будує ключ через `HashUtils.computeKey()`.
2. `instances.getOrPut(key) { RoutingInstance() }` — або знаходить існуючий
   запис, або створює новий.
3. Додає `hostEntry` до списку `hosts`.
4. Якщо є RD — реєструє в `vrfVplsList` для RD-індексу.

`@Synchronized` потрібен бо `merge()` викликається з паралельних потоків і
виконує складену операцію (кілька кроків як одна транзакція) над
`TreeMap`/`LinkedHashMap` без власної потокової безпеки. `@JvmStatic`
дозволяє викликати без посилання на синглтон-`Companion` — `RoutingInstance.merge(...)`.

**`HashUtils`** — Kotlin `object` (синглтон). `computeKey()` будує ключ
дедублікації, сумісний з оригінальною Perl-реалізацією:
```
"ім'я, доповнене до 50 символів" + ":" + MD5(ім'я+тип) + ":" + SHA1(ім'я+тип)
```
Завдяки цьому один VRF на 10 роутерах дає один рядок у звіті з переліком
всіх 10 роутерів.

---

### `LoAddressMapper`

`internal object`. Читає `xmlCache` (або диск) для кожного Juniper-хосту і
будує словник `lo0-адреса → ім'я роутера`. XPath:
```
//interfaces/interface[name='lo0']/unit/family/*/address/name
```
Зірочка `*` охоплює будь-яке сімейство адрес (inet, inet6) без їх явного
перерахування. Адреси зберігаються без префікс-довжини (`/32`, `/128`).

Цей словник використовується у двох місцях:
- `ReportGenerator` — замінює IP-адреси сусідів у головній таблиці на
  `ROUTERNAME/IP`.
- `JuniperDownStateCollector` — те саме для down-state таблиці.

---

### `InterfaceRegistry`

Звичайний клас, конструктор `(xmlCache: ConcurrentHashMap<String, String>)`.
Один екземпляр створюється у `main()` і передається всім п'яти
Juniper-колекторам через `AbstractJuniperCollector`.

Для кожного хоста ліниво (на перший запит) парсить XML і будує множину
ідентифікаторів інтерфейсів, описаних у блоці верхнього рівня
`<interfaces>` — і голу назву (`xe-1/2/0`, `irb`), і кожен `<unit>` як
`name.unit` (`xe-1/2/0.130`, `irb.640`). Атрибут `inactive="inactive"` НЕ
виключає інтерфейс із реєстру: оператор все одно його сконфігурував, а
inactive показується окремим маркером `(-)`.

Метод:
```kotlin
fun isMissing(hostname: String, ifaceName: String): Boolean
```

Повертає `true`, тільки коли XML хоста був успішно завантажений і вказаного
інтерфейсу в ньому немає. Якщо XML недоступний (не було в кеші, нема на
диску, помилка парсингу) — повертає `false` для всіх запитів. Це навмисно:
один тимчасовий збій SSH/NETCONF не повинен вибухнути потоком фальшивих
`(!)` по всьому звіту.

Збереження статусу «неможливо завантажити» реалізоване через приватний
sentinel `UNAVAILABLE: Set<String> = HashSet()` у `companion object`, який
кладеться в кеш замість справжньої множини при провалі завантаження.

---

### `LtTunnelLinker` + `ifaceIndex`

Логічні тунелі Juniper (`lt-X/Y/Z.A` ↔ `lt-X/Y/Z.B`) — це парні логічні
інтерфейси, які склеюють два сервіси на одному маршрутизаторі. Класичний
приклад: одна сторона `vlan-bridge` потрапляє у `bridge-domain`, друга
`vlan-ccc` — у `protocols/l2circuit`. Щоб зрозуміти топологію «що з чим
зчеплено», треба зіставити обидві сторони з їхніми сервісами.

**Reverse-індекс `ifaceIndex`.** Звичайний `ConcurrentHashMap<String,
ConcurrentHashMap<String, IfaceRef>>`, створюється у `main()` поруч із
`instances` і пробрасується через `AbstractJuniperCollector` у всі п'ять
Juniper-колекторів. Чотири конфіг-парсери — `JuniperCollector`,
`JuniperBridgedomainsCollector`, `JuniperL2circuitCollector`,
`JuniperSwitchCollector` — під час обходу інтерфейсних посилань кладуть у
нього `(hostname.uppercase(), ifaceName) → IfaceRef(instanceName, typeUpper)`.
`computeIfAbsent` на ConcurrentHashMap робить вкладене створення внутрішньої
мапи безпечним для паралельних викликів.

**Пара даних** (у `LtTunnelLinker.kt`):

```kotlin
data class IfaceRef(val instanceName: String, val type: String)
data class LtSide(val iface: String, val instanceName: String, val type: String)
data class LtLink(val router: String, val sideA: LtSide, val sideB: LtSide)
```

`LtTunnelLinker.build(hosts, xmlCache, ifaceIndex)` для кожного хоста:

1. Парсить `<interfaces>/interface[starts-with(name,'lt-')]/unit`, збирає
   `(unitName, peerUnit, inactive)`.
2. Групує одиниці, що **взаємно** вказують одна на одну через `<peer-unit>`
   — однобічна асиметрія сприймається як зламаний конфіг і відкидається.
3. Для кожної пари ставить меншу одиницю на сторону A (числовий sort key, з
   фолбеком на лексикографічний для нечислових імен) — щоб одна фізична
   пара не дублювалася як `A→B` і `B→A`.
4. Для кожної сторони шукає `IfaceRef` у `ifaceIndex[router][full-iface-name]`.
   Якщо ніхто не «застовпив» цю одиницю — `instanceName="?"`, `type="?"`,
   щоб «висячі» половинки тунелю були видні в звіті, а не тихо зникали.

**Маркер `(-)`.** Якщо одиниця має `inactive="inactive"`, до її `iface`
додається `(-)`, який потім `ReportGenerator.colorize()` перетворює на
magenta-span. Маркер `(!)` тут не виникає — `lt-*/<unit>` за визначенням
описаний у `<interfaces>` (інакше lt-тунель не існував би), тож
`InterfaceRegistry.isMissing` поверне `false`.

**Читання — без мережі.** LtTunnelLinker працює тільки з `xmlCache` і
дисковими дампами; нових SSH/NETCONF-сесій не відкриває. Хости без XML
тихо пропускаються (LT-таблиця для них буде порожня — те саме поводження,
що в `LoAddressMapper`).

---

### `RoutingInstancesReport` (точка входу)

Файл анотовано `@file:JvmName("RoutingInstancesReport")`, тому верхньорівнева
функція `main()` доступна як `net.ukrhub.routing.instances.report.RoutingInstancesReport.main`
— саме це і вказано як `Main-Class` у JAR-маніфесті.

`main()` читає env vars, створює спільні структури (`instances`, `vrfVplsList`,
`xmlCache`, `ifaceRegistry`, `semaphore`), запускає три фази, будує lo0-карту,
шукає orphans, збирає down-state, генерує звіт.

Два приватних хелпери:
- `runParallel(hosts, task)` — подає задачі у virtual-thread executor і чекає
  всіх через `.use { }`.
- `findOrphans(instances, loAddresses)` — для кожного L2CIRCUIT і VPLS з
  ключем `число/ROUTER` перевіряє чи є парний запис на сусідньому кінці.
  Використовує `vcidRouterSet: Map<vcId, Set<routerName>>` — набір усіх
  роутерів, що мають запис для даного VC-ID (незалежно від типу L2CIRCUIT чи
  VPLS, бо вони можуть бути парою одне одному).

---

### `ReportGenerator`

Kotlin `object`. Метод `generate()` приймає всі зібрані дані і будує HTML
рядковою заміною у шаблоні (`HTML_TEMPLATE`). Шість розділів:

1. **VRF/VPLS за RD** — `buildVrfList()`: сортування за числовим добутком
   AS×ID з RD-рядка.
2. **VC-ID/VPLS-ID** — `buildVcidList()`: групування записів `число/ROUTER`
   за числовим префіксом.
3. **Основна таблиця** — `buildVrfInfo()`: IP-адреси в колонці Маршрутизатор
   замінюються на `ROUTERNAME/IP` регулярним виразом по словнику lo0;
   інтерфейси та роутери з маркерами розфарбовуються (див. нижче).
4. **L2CIRCUIT/VPLS без пар** — `buildOrphanTable()`.
5. **L2CIRCUIT/VPLS неактивний стан** — `buildDownStateTable()`: сортування
   за типом → роутер → числовий VC-ID → instance.
6. **Логічні тунелі (lt-*)** — `buildLtLinkTable()`: одна стрічка на пару
   `lt-X.A ↔ lt-X.B`, з посиланнями на основну таблицю для обох сервісів.
   Сторона з `instanceName == "?"` показується без посилання — означає
   «висячий» кінець тунелю.

**`colorize()` — підсвітка маркерів.** Регулярний вираз
`[A-Za-z0-9._/:-]+(?:\(-\)|\(!\))+` ловить кожен токен виду `name(-)` /
`name(!)` / `name(-)(!)`. Якщо в токені є `(!)` — обгортає у
`<span style="color:crimson">…</span>`, інакше у
`<span style="color:magenta">…</span>`. Маркер у видимому тексті
зберігається. `colorize()` застосовується **після** `h()` і резолву IP, але
**після** виклику `log.info` — щоб у логах залишився чистий текст без
HTML-обгорток.

---

## Ключові патерни та рішення

### Чому XPath, а не JSON/RESTCONF?

Juniper JunOS повертає конфігурацію у XML. NETCONF — стандартний протокол
управління мережевим обладнанням (RFC 6241), підтримується всіма сучасними
Juniper-пристроями. XPath дозволяє точно описати що саме шукати у XML без
ручного парсингу.

### Чому Telnet для Cisco, а не SSH?

Старі версії Cisco IOS не підтримують NETCONF, а SSH на них може бути
вимкнений або не налаштований. Telnet — найменший спільний знаменник.

### Чому `LinkedHashMap` для `vrfVplsList`, а не `ConcurrentHashMap`?

`LinkedHashMap` зберігає порядок вставки. У RD-індексі важливо щоб записи
йшли у тому порядку, в якому вперше зустрічалися при зборі — так
пов'язані контури стоять поруч. `ConcurrentHashMap` порядку не гарантує.
Потокова безпека забезпечується через `@Synchronized merge()`, а не через
concurrent-колекцію.

### Чому `ConcurrentHashMap` для `xmlCache`?

Тут порядок не важливий — кожен хост пише свій унікальний ключ. Читачі фази 2
звертаються одночасно до різних ключів. `ConcurrentHashMap` тут ідеальний:
`put` і `get` на різних ключах не конфліктують і не потребують зовнішньої
синхронізації. `InterfaceRegistry` всередині також тримає
`ConcurrentHashMap<String, Set<String>>` з тих самих причин.

### Чому верхньорівневі логери, а не Lombok-`@Log4j2`?

Kotlin не має Lombok-аналога — і не потребує. У кожному `.kt`-файлі логер
оголошується **на рівні файлу** одним рядком:

```kotlin
private val log: Logger = LogManager.getLogger(SomeClass::class.java)
```

`private` робить його видимим лише в цьому файлі (file-private у Kotlin), а
`val` — `final` і потокобезпечно ініціалізованим. Це настільки ж лаконічно
як `@Log4j2`, без додаткової залежності.

---

## Потокова безпека

| Структура | Тип | Захист |
|-----------|-----|--------|
| `instances` | `TreeMap` | `@Synchronized merge()` |
| `vrfVplsList` | `LinkedHashMap` | `@Synchronized merge()` |
| `xmlCache` | `ConcurrentHashMap` | вбудований (lock-striped) |
| `InterfaceRegistry.cache` | `ConcurrentHashMap` | `computeIfAbsent` ідемпотентний |
| `downConnections` | `Collections.synchronizedList(mutableListOf())` | вбудований |
| Поля колекторів | `val` (final) | незмінні, захисту не потребують |

`AbstractJuniperCollector` не має змінюваного стану (поле `leftover` є
локальною змінною у `fetchRpcs()`) — тому один екземпляр колектора можна
безпечно використовувати з кількох потоків одночасно. `InterfaceRegistry` теж
безстатковий зовні: всередині — `ConcurrentHashMap`, побудова через
`computeIfAbsent` ідемпотентна.

---

## Безпека та відомі компроміси

### `StrictHostKeyChecking=no`

У всіх SSH-підключеннях (Juniper NETCONF, RouterOS) задано:

```kotlin
val cfg = Properties().apply {
    put("StrictHostKeyChecking", "no")
    put("PreferredAuthentications", "password,keyboard-interactive")
}
```

Це означає, що клієнт **не перевіряє** SSH-відбиток хоста і не зберігає
`known_hosts`. При першому підключенні до нового пристрою ключ просто
приймається без запиту.

**Чому так зроблено:**
- Інструмент працює у закритій мережі управління (out-of-band management
  network), ізольованій від публічного інтернету.
- Маршрутизатори не змінюють IP-адреси і не є мобільними вузлами.
- Підтримка `known_hosts` у Docker-контейнері потребувала б або монтування
  постійного тому, або першого ручного підтвердження ключа — ускладнення без
  реальної вигоди в цьому середовищі.

**Який ризик залишається:**
Якщо зловмисник отримає доступ до management-мережі і підмінить IP-адресу
маршрутизатора — MITM-атака стане можливою: клієнт підключиться до
підробленого хоста без попередження. У відкритих мережах
`StrictHostKeyChecking=no` є суттєвою вразливістю.

**Як посилити за необхідності:**
Замінити `"no"` на `"ask"` або `"yes"` і змонтувати `known_hosts`-файл у
контейнер:
```yaml
volumes:
  - ./known_hosts:/root/.ssh/known_hosts:ro
```
або задати `UserKnownHostsFile` у JSch-конфігурації.

### XXE — XML External Entity

Парсер Juniper XML використовує `DocumentBuilderFactory` з увімкненим:
- `XMLConstants.FEATURE_SECURE_PROCESSING = true`
- `http://apache.org/xml/features/disallow-doctype-decl = true`

Це блокує `<!DOCTYPE ...>` директиви у XML, запобігаючи атакам XXE і
розгортанню зовнішніх сутностей. На практиці всі дані надходять від
власних маршрутизаторів (довірених пристроїв), тому ризик мінімальний,
але захист дешевий і варто його мати.

### HTML escaping у звіті

`ReportGenerator.h()` екранує `&`, `<`, `>`, `"`, `'` у всіх даних, що
надходять від маршрутизаторів: імена інстансів, RD-рядки, записи хостів,
коди статусів. Це захист від XSS на випадок якщо конфігурація пристрою
містить символи HTML. Кольорова обгортка (`<span style="color:…">`) додається
**після** екранування і використовує тільки внутрішньо контрольовані рядки —
тож `colorize()` сама XSS не вносить.

---

## Протокол NETCONF

NETCONF (RFC 6241) — це XML-протокол для управління мережевим обладнанням
поверх SSH. Спрощена діаграма сесії:

```
Клієнт                                    Роутер
  │                                          │
  │──── SSH connect ────────────────────────▶│
  │◀─── <hello> (можливості роутера) ────────│  ← readUntilDelimiter (server hello)
  │──── <hello> (наші можливості) + ]]>]]> ─▶│  ← send(NETCONF_HELLO)
  │                                          │
  │──── <rpc> get-config </rpc> + ]]>]]> ───▶│  ← send(rpc)
  │◀─── <rpc-reply> ... </rpc-reply> ]]>]]>  │  ← readUntilDelimiter (відповідь)
  │                                          │
  │──── <rpc> close-session </rpc> + ]]>]]> ▶│
  │                                          │
  │──── SSH disconnect ─────────────────────▶│
```

`]]>]]>` — роздільник NETCONF 1.0 (`DELIM`). Він сигналізує кінець кожного
повідомлення, бо XML може мати будь-яку довжину і клієнт інакше не знав би
коли повідомлення закінчилося.

`readUntilDelimiter()` читає байти з потоку поки не зустріне `]]>]]>`. Байти
що прийшли після роздільника (наступне повідомлення вже «в дорозі») зберігаються
у `leftover` і використовуються на початку наступного виклику — щоб не втратити
жодного байта.

---

## Як побудувати та запустити локально

```bash
# Зібрати fat JAR (містить всі залежності)
mvn package -DskipTests

# Запустити проти одного роутера (підставте реальні значення)
ROUTER_USER=admin \
ROUTER_PASS=secret \
JUNIPER_HOSTS=my-router-1 \
REPORT_PATH=/tmp/report.html \
LOG_LEVEL=debug \
java -jar target/routing-instances-report-1.0.jar

# Зібрати Docker-образ
docker build -t routing-instances-report .

# Перегенерувати діаграму класів і Dokka-документацію
mvn -P docs generate-resources
# → docs/classes.svg               (PlantUML)
# → target/reports/dokka/          (Dokka, HTML)
```

---

## Як додати підтримку нового вендора

Припустимо, треба додати підтримку **Huawei VRP**.

### 1. Створити клас колектора

```kotlin
package net.ukrhub.routing.instances.report

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

private val log: Logger = LogManager.getLogger(HuaweiCollector::class.java)

class HuaweiCollector(
    private val login: String,
    private val pass: String,
) : Collector {

    override fun collect(
        hostname: String,
        instances: MutableMap<String, RoutingInstance>,
        vrfVplsList: MutableMap<String, MutableMap<String, String>>,
    ) {
        // підключитися до роутера (SSH, Telnet, NETCONF — як підтримує пристрій)
        // розібрати відповідь
        // для кожного знайденого сервісу:
        RoutingInstance.merge(instances, vrfVplsList, name, type, rd, hostEntry)
    }
}
```

### 2. Додати env var

У `RoutingInstancesReport.main()`:
```kotlin
val huaweiHosts = parseList(env("HUAWEI_HOSTS", ""))
```

### 3. Додати у фазу 2

```kotlin
val huawei = HuaweiCollector(login, pass)
runParallel(huaweiHosts) { host ->
    semaphore.acquireUninterruptibly()
    try {
        huawei.collect(host, instances, vrfVplsList)
    } finally {
        semaphore.release()
    }
}
```

### 4. Якщо пристрої Huawei теж мають перевірятися на «мертві» інтерфейси

`InterfaceRegistry` зараз спеціалізований під формат `<interfaces>` Juniper.
Для нового вендора або (a) реалізуйте власний реєстр з тією самою сигнатурою
`isMissing(hostname, ifaceName)`, або (b) узагальніть `InterfaceRegistry`
взявши лямбду-екстрактор як параметр.

### 5. Задокументувати env var у README і CONTRIBUTING.md

---

## Стиль коду

- **Kotlin 2.1**, цільовий байткод **JVM 21** (`maven.compiler.release=21`,
  `jvmTarget=21` у `kotlin-maven-plugin`).
- **Без Lombok** — Kotlin генерує геттери/сеттери для `var`, `data class` дає
  `equals`/`hashCode`, top-level `private val log` замінює `@Log4j2`.
- **Логери** — оголошуйте на рівні файлу:
  ```kotlin
  private val log: Logger = LogManager.getLogger(SomeClass::class.java)
  ```
- **Документація** — KDoc на публічних класах, об'єктах і методах. Не
  дублюйте у коментарях те, що видно з імен. Перегенерується через
  `mvn -P docs generate-resources` (Dokka → `target/reports/dokka/`,
  PlantUML → `docs/classes.svg`). Без фази `generate-resources` Maven просто
  активує профіль і нічого не запускає.
- **Видимість** — Kotlin за замовчуванням `public`. Внутрішні утиліти
  (`LoAddressMapper`, `ConnectionStatus`) позначайте `internal`. Класи, що
  попадають у `public` API колекторів (як `InterfaceRegistry`), залишайте
  `public`, інакше Kotlin поскаржиться на «exposes its 'internal' parameter».
- **Логування** — `log.info` для подій, важливих оператору, `log.debug` для
  деталей при діагностиці, `log.warn` для проблем, що не зупиняють виконання.
- **Обробка помилок** — колектори кидають `Exception` наверх; `main()` їх
  ловить, логує і продовжує з наступним хостом. Не ковтати помилки мовчки.
- **Тести** — наразі відсутні (основний інтеграційний тест — Docker-збірка і
  реальні роутери). Якщо додаєте юніт-тести — `src/test/kotlin/...`, той
  самий пакет.
