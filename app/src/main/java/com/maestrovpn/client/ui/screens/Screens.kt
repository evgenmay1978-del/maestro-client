package com.maestrovpn.client.ui.screens

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.maestrovpn.client.BuildConfig
import com.maestrovpn.client.R
import com.maestrovpn.client.core.engine.EngineManager
import com.maestrovpn.client.core.subscription.SubscriptionInfo
import com.maestrovpn.client.core.subscription.SubscriptionManager
import com.maestrovpn.client.ui.TelegramButtons
import com.maestrovpn.client.ui.navigation.Routes
import com.maestrovpn.client.ui.navigation.ScreenSpec
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

// Заглушка экрана (срез 1): заголовок + что здесь будет + ссылка на карту Happ.
@Composable
fun StubScreen(spec: ScreenSpec) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(spec.title)
        Text(stringResource(R.string.stub_title))
        Text(stringResource(R.string.stub_body, spec.description))
        Text(stringResource(R.string.stub_source, spec.mapRef))
    }
}

// Настройки (срез 1): настоящий список разделов, каждый ведёт на свой экран.
@Composable
fun SettingsScreen(navController: NavController) {
    val items = listOf(
        Routes.VPN_SETTINGS to "Настройки VPN",
        Routes.INBOUND_AUTH to "Inbound-авторизация",
        Routes.EXCLUDED_ROUTES to "Исключённые маршруты",
        Routes.PER_APP_PROXY to "Per-app proxy",
        Routes.PING_SETTINGS to "Настройки пинга",
        Routes.STATISTICS to "Статистика",
        Routes.LOGS to "Логи",
        Routes.ROUTING to "Маршрутизация",
        Routes.SETTINGS_ADVANCED to "Продвинутые настройки",
        Routes.SETTINGS_TUNNEL to "Настройки туннеля",
        Routes.SETTINGS_UI to "Интерфейс",
        Routes.SETTINGS_LANGUAGE to "Язык",
        Routes.FAQ to "FAQ",
        Routes.ABOUT to "О приложении",
        Routes.REPORT to "Сообщить о проблеме",
        Routes.RESET to "Сброс",
        Routes.APP_UPDATE to "Обновление приложения",
    )
    LazyColumn(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        item { Text(stringResource(R.string.settings_title)) }
        items(items) { (route, title) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { navController.navigate(route) }
                    .padding(vertical = 14.dp),
            ) {
                Text(title)
            }
        }
    }
}

// О приложении (срез 1): версии, устройство, Telegram. Других ссылок нет.
@Composable
fun AboutScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.about_title))
        Text(stringResource(R.string.about_version) + ": ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        Text(stringResource(R.string.about_engine) + ": ${EngineManager.engine.version}")
        Text(
            stringResource(R.string.about_device) + ": ${Build.MODEL} (Android ${Build.VERSION.RELEASE})",
        )
        Spacer(Modifier.height(8.dp))
        TelegramButtons()
    }
}

// Список подписок (срез 1): чтение/обновление/удаление через SubscriptionManager.
@Composable
fun SubscriptionsScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { SubscriptionManager(context, EngineManager.engine) }
    var subs by remember { mutableStateOf(manager.list()) }
    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text(stringResource(R.string.subscriptions_title))
        Spacer(Modifier.height(12.dp))
        Button(onClick = { navController.navigate(Routes.SUBSCRIPTION_ADD) }) {
            Text(stringResource(R.string.add_subscription))
        }
        Spacer(Modifier.height(12.dp))
        val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        subs.forEach { sub ->
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(sub.name)
                    Text(sub.url, maxLines = 1)
                    Text(fmt.format(Date(sub.lastUpdatedMillis)))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch {
                                manager.refresh(sub)
                                subs = manager.list()
                            }
                        }) { Text(stringResource(R.string.sub_refresh)) }
                        Button(onClick = {
                            manager.delete(sub)
                            subs = manager.list()
                        }) { Text(stringResource(R.string.sub_delete)) }
                    }
                }
            }
        }
    }
}

// Добавление подписки (срез 1): URL → скачать → validate-before-save → сохранить.
@Composable
fun SubscriptionAddScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { SubscriptionManager(context, EngineManager.engine) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.add_subscription))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.sub_name)) },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text(stringResource(R.string.sub_url_hint)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = !busy && url.isNotBlank(),
            onClick = {
                busy = true
                message = context.getString(R.string.sub_downloading)
                scope.launch {
                    val result = runCatching {
                        val content = manager.download(url.trim()).getOrThrow()
                        val info = SubscriptionInfo(
                            id = UUID.randomUUID().toString(),
                            name = name.ifBlank { url.trim() },
                            url = url.trim(),
                            lastUpdatedMillis = System.currentTimeMillis(),
                            fileName = UUID.randomUUID().toString() + ".json",
                        )
                        manager.saveValidated(info, content).getOrThrow()
                    }
                    busy = false
                    if (result.isSuccess) {
                        message = context.getString(R.string.sub_saved)
                        navController.popBackStack()
                    } else {
                        message = (context.getString(R.string.sub_invalid) + ": " +
                            (result.exceptionOrNull()?.message ?: ""))
                    }
                }
            },
        ) {
            Text(stringResource(R.string.sub_add))
        }
        message?.let { Text(it) }
    }
}
