# SCCollector

Плагин для Bukkit, Spigot и Paper. Читает состояние SimpleClans и отправляет снапшоты в SimpleClansStats по HTTP.

## Сборка и установка

1. Соберите плагин с JDK 17 или новее. На Windows выполните `gradlew.bat build`, на Linux/macOS — `./gradlew build`.
2. Положите `build/libs/SCCollector-0.1.0.jar` и SimpleClans 2.19.2 в папку `plugins` и запустите сервер.
3. Выполните команду из диалога добавления сервера или заполните `plugins/SCCollector/config.yml` и перезапустите сервер.

```text
/scstats config apply --endpoint https://stats.example.com/api/v1/ingest --api-key sc-...
```

Команда требует право `sccollector.configure` (по умолчанию у операторов). Она проверяет параметры, сохраняет их в конфиге и запускает отправку без перезагрузки сервера. В консоли сервера начальный слеш опускается. Если сервер или сторонний плагин журналирует команды, для ключа используйте `config.yml`.

```yaml
endpoint: 'https://stats.example.com/api/v1/ingest'
api-key: 'sc-...'
server-id: ''
interval-seconds: 300
collect-claims: false
```

`server-id` можно оставить пустым. SimpleClansStats определяет сервер по ключу, а если указан `server-id`, сверяет его с UUID зарегистрированного сервера. Интервал по умолчанию — 5 минут, минимальный — 5 секунд. Для локальной разработки разрешён HTTP на loopback. Для остальных адресов нужен HTTPS.

Плагин компилируется в байткод Java 8 с API Spigot 1.8.8 и SimpleClans 2.19.2. На каждой версии Minecraft сервер, SimpleClans и прочие плагины должны поддерживать используемую JVM. Исходники SimpleClans сверены с коммитом [35065bf](https://github.com/RoinujNosde/SimpleClans/commit/35065bf).

## Контракт Ingest

Плагин отправляет JSON через `POST /api/v1/ingest` с заголовком `X-API-Key`. Базовые поля из `shared/openapi_raw.yaml` в SimpleClansStats — `takenAt`, опциональный `serverId`, `clans` и `players`. Приём подтверждается HTTP 202 и JSON с `"accepted": true`.

Идентификатор клана — тег из SimpleClans без цветов. При смене тега ключ меняется, событие `clan.tagChanged` связывает прежний и новый ключи. Ссылки на игроков используют UUID. Список `players` содержит профили, известные SimpleClans, и видимых онлайн-игроков без профиля. В `role` передаётся `leader` или `member`, в `rank` — название настроенного ранга. Для игроков без клана поля принадлежности равны `null`. Поле `verified` у игрока соответствует флагу доверия в SimpleClans.

| Данные | Где читать |
| --- | --- |
| Счётчики, рейтинг, состав, карточки, сравнение и экономика | `clans[]` и `players[]`. Дополнительные поля клана — `colorTag`, `foundedAt`, `kdr`, `kills`, `deaths`, `allyExternalIds`, `rivalExternalIds`. У игрока — `rank`, `lastSeenAt`, `pastClanExternalIds`, `kdr`, `killsByType` с ключами `ally`, `rival`, `civilian`, `neutral`. |
| Текущий онлайн | `extensions.scCollectorV1.onlinePlayers[]` с `uuid`, `name`, `clanExternalId`. NPC и игроки с Bukkit-метаданными `vanished: true` исключены. |
| Войны и связи | `extensions.scCollectorV1.wars[]`. Одна запись на пару кланов с `clanExternalIds` и `casualties` по кланам, если эти данные есть в SimpleClans. Союзы и соперничества передаются в полях клана. |
| Активность и PvP | `extensions.scCollectorV1.events[]`. Общие поля — `id`, `type`, `occurredAt`. Дополнительные поля зависят от типа события. |
| Приглашения | `extensions.scCollectorV1.pendingInvites[]` с `clanExternalId`, `playerName`, `inviterUuid`. Доступны приглашения, замеченные с момента запуска плагина. |
| Территории | Опциональное `extensions.scCollectorV1.claims[]` с `id`, `worldUuid`, `clanExternalId`. Заполняется через установленные интеграции земель SimpleClans при `collect-claims: true`. |
| Ряды, пики онлайна, дельты за 24 часа, лидеры роста и падения | Ingest должен сохранять историю снапшотов по `takenAt`. Для карты активности используются также даты событий. |

Типы событий — `clan.created`, `clan.disbanded`, `clan.tagChanged`, `clan.verified`, `clan.unverified`, `clan.balanceChanged`, `player.joinedClan`, `player.leftClan`, `war.started`, `war.ended`, `player.pvpKill`. Верификация и смена состояния войны определяются между последовательными снимками. PvP-лента отражает смерти с убийцей-игроком по Bukkit. Накопительные счётчики берутся из SimpleClans, где действуют его собственные фильтры и правила начисления.

При сбое отправки следующий снапшот повторно включает неподтверждённые события с теми же `id`. Приёмнику следует устранять дубликаты по серверу и `id` события. Очередь хранит до 1000 событий в памяти и сбрасывается при перезапуске или применении конфигурации.

В SimpleClansStats откройте «Настройки → Серверы → Добавить сервер». После регистрации диалог покажет адрес, одноразово выдаваемый ключ и готовую команду для SCCollector. Ingest принимает JSON до 8 МиБ и записывает историю только при изменении состояния. В разделе «Настройки → Серверы» задаются таймаут онлайна (по умолчанию 10 минут) и срок хранения истории и событий (по умолчанию 30 дней с момента приёма). Срок `0` отключает очистку. Последнее состояние каждого сервера хранится отдельно.
