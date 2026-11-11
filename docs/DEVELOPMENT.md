# Разработка на nadein-core

Рабочее дерево: `/home/igor/Dev/family-tree-android`.
`origin`: `IgorNadein/family-tree-android`; `upstream`: `michelesalvador/FamilyGem`.

## Android identity

- `applicationId = ru.nadein.familytree` — идентификатор установки и пространства данных Android.
- `namespace = app.familygem` временно оставлен для совместимости с исходниками Kotlin/Java и сгенерированными классами. Это **не** package name устанавливаемого APK.
- Видимое имя: «Семейное древо» (ru) / «Family Tree» (по умолчанию).
- FileProvider использует `${applicationId}.provider`; deep links оригинального сайта Family Gem удалены.
- Версия форка: `0.2.3 (8)`, отдельно от версий Family Gem.

## Сборка

Используется Android Gradle Plugin 9.3.1 и Kotlin Gradle Plugin 2.4.21 (без устаревшего `kotlin-android-extensions`). Встроенный Kotlin AGP отключён: он использовал несовместимый с библиотеками проекта компилятор 2.2.10. Для KGP 2.4.21 временно заданы `android.builtInKotlin=false` и `android.newDsl=false`; это переходные флаги AGP 9, которые надо убрать при миграции на AGP 10. Gradle закреплён версией 9.6.0 в wrapper; для сборки требуются Java 17+ и Android SDK 37.2. На `nadein-core` применяем установленную Java 21, при этом JVM target Kotlin и Java-кода остаётся 1.8 (это важно для совместимости двух компиляторов и minSdk 23). Путь к локально установленному Android SDK нужно указать через `ANDROID_HOME` либо `local.properties`; ни то ни другое не хранится в Git.

Команды сборки (на nadein-core Android SDK установлен в `~/Android/Sdk`):

```bash
cd ~/Dev/family-tree-android
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

CI: `.github/workflows/android.yml` запускает unit-тесты, компилирует debug и instrumentation APK, а на отдельном эмуляторе API 35 запускает UI-тест открытия дерева при push/PR в `master`. Успешный запуск для 0.1.1: https://github.com/IgorNadein/family-tree-android/actions/runs/42931664825. Артефакт `family-tree-android-debug` сохраняется 14 дней; он не заменяет подписанный релиз.

11 ноября 2026: проверенная исходная версия 0.1.1 успешно собрана на nadein-core (1 unit test, 0 ошибок). APK: `app/build/outputs/apk/debug/app-debug.apk`, версия `0.1.1 (2)`, пакет `ru.nadein.familytree`. Проверка на физическом устройстве ещё нужна. AGP 9.3.1 предупреждает, что SDK 37.2 новее протестированного SDK 37.1; поскольку сборка прошла, это пока зафиксировано как технический долг.

Релизный APK должен подписываться **отдельным** ключом (не ключами других приложений); ключи и пароли не хранить в Git.

## Следующие этапы

- **0.1.1 — выполнено:** воспроизводимая сборка на nadein-core, Kotlin 2.4.21, Gradle wrapper, CI и отладочный APK.
- **0.2.0 — первый этап архитектуры:** `TreeRepository`, атомарная запись файлов, тесты GEDCOM и JSON, управляемые корутины вместо `GlobalScope`. Остаток работ — постепенное удаление `Global` из экранов, перенос прочих путей сохранения и независимая реализация онлайн-функций.
- **0.2.1 — стабилизация перед Compose:** восстановлен старый интерфейс, выделены `TreeListViewModel`/`TreeSelectionPolicy`/`TreeMenuPolicy`/`OpenTreeUseCase` и `LegacyTreeListGateway`, добавлен Android UI-тест на открытие дерева.
- **0.2.2 — родственные связи:** явный выбор семьи ребёнка, запрет автоматического выбора первого мужа при нескольких браках, регрессионные тесты GEDCOM и UI.
- **0.3.0 — современный UI:** Jetpack Compose + Material 3, дизайн-система, основные экраны, диалоги и навигация.
- **0.4.0 — карточки и редакторы:** профили родственников, события, связи и фотографии.
- **0.5.0 — визуализация:** интерфейс древа поверх существующего графового движка, жесты, масштабирование и экспорт.

До следующего релиза: отдельная подпись, проверка APK на Android-устройстве, тесты импорта/экспорта и резервного копирования. Никакие функции онлайн-обмена деревьями с `familygem.app` пока не перенесены на собственный сервер.

Детали слоя хранения, гарантии совместимости и ограничения: [ARCHITECTURE.md](ARCHITECTURE.md).

- **0.2.3 — .ftree:** снимки GEDCOM, история сохранений, экспорт ZIP, импорт копий и трёхстороннее слияние с разрешением конфликтов. Ограничения: [VERSIONED_ARCHIVES.md](VERSIONED_ARCHIVES.md).
