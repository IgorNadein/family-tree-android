# Разработка на nadein-core

Рабочее дерево: `/home/igor/Dev/family-tree-android`.
`origin`: `IgorNadein/family-tree-android`; `upstream`: `michelesalvador/FamilyGem`.

## Android identity

- `applicationId = ru.nadein.familytree` — идентификатор установки и пространства данных Android.
- `namespace = app.familygem` временно оставлен для совместимости с исходниками Kotlin/Java и сгенерированными классами. Это **не** package name устанавливаемого APK.
- Видимое имя: «Семейное древо» (ru) / «Family Tree» (по умолчанию).
- FileProvider использует `${applicationId}.provider`; deep links оригинального сайта Family Gem удалены.
- Версия форка: `0.1.1 (2)`, отдельно от версий Family Gem.

## Сборка

Используется Android Gradle Plugin 9.3.1 и Kotlin Gradle Plugin 2.4.21 (без устаревшего `kotlin-android-extensions`). Встроенный Kotlin AGP отключён: он использовал несовместимый с библиотеками проекта компилятор 2.2.10. Gradle закреплён версией 9.6.0 в wrapper; для сборки требуются Java 17+ и Android SDK 37.2. На `nadein-core` применяем установленную Java 21. Путь к локально установленному Android SDK нужно указать через `ANDROID_HOME` либо `local.properties`; ни то ни другое не хранится в Git.

Команды сборки после настройки инструментов:

```bash
cd ~/Dev/family-tree-android
./gradlew :app:assembleDebug
```

CI: `.github/workflows/android.yml` автоматически запускает тесты и собирает отладочный APK при push/PR в `master`. Артефакты доступны в GitHub Actions; они не заменяют подписанный релиз.

Релизный APK должен подписываться **отдельным** ключом (не ключами других приложений); ключи и пароли не хранить в Git.

## План

1. Настроить Gradle wrapper и Android SDK; проверить debug-сборку.
2. Отдельные ключи подписи, оформление приложения и выпуск APK.
3. Отвязать онлайн-функции от сервисов Family Gem или реализовать свой backend.
4. После проверки базовых функций — модернизировать интерфейс, не ломая GEDCOM, карточки людей и связи.
