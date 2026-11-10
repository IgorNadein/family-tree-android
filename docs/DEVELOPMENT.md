# Разработка на nadein-core

Рабочее дерево: `/home/igor/Dev/family-tree-android`.
`origin`: `IgorNadein/family-tree-android`; `upstream`: `michelesalvador/FamilyGem`.

## Android identity

- `applicationId = ru.nadein.familytree` — идентификатор установки и пространства данных Android.
- `namespace = app.familygem` временно оставлен для совместимости с исходниками Kotlin/Java и сгенерированными классами. Это **не** package name устанавливаемого APK.
- Видимое имя: «Семейное древо» (ru) / «Family Tree» (по умолчанию).
- FileProvider использует `${applicationId}.provider`; deep links оригинального сайта Family Gem удалены.
- Версия форка начинается с `0.1.0 (1)`, отдельно от версий Family Gem.

## Сборка

Проект унаследован с Android Gradle Plugin 9.4.0, Kotlin Gradle Plugin 2.3.21 и compile SDK 37.2. Для сборки нужны совместимый Gradle и Android SDK. В исходном репозитории **нет Gradle wrapper**; для воспроизводимой сборки необходимо закрепить совместимую версию Gradle и добавить wrapper. На nadein-core установлена Java 21, но полноценный Android SDK/Gradle пока не настроен.

Команды сборки после настройки инструментов:

```bash
cd ~/Dev/family-tree-android
./gradlew :app:assembleDebug
```

Релизный APK должен подписываться **отдельным** ключом (не ключами других приложений); ключи и пароли не хранить в Git.

## План

1. Настроить Gradle wrapper и Android SDK; проверить debug-сборку.
2. Отдельные ключи подписи, оформление приложения и выпуск APK.
3. Отвязать онлайн-функции от сервисов Family Gem или реализовать свой backend.
4. После проверки базовых функций — модернизировать интерфейс, не ломая GEDCOM, карточки людей и связи.
