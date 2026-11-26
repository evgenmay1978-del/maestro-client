package com.maestrovpn.client.ui.screens

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.maestrovpn.client.core.routing.RoutingProfile
import com.maestrovpn.client.core.routing.RoutingProfileStore

/**
 * Экраны маршрутизации (раздел 4, issue #2): список профилей, редактор,
 * правила (порядок block/proxy/direct), DNS-зоны, привязка к подписке.
 *
 * Все данные — через [RoutingProfileStore], без выдуманных полей.
 */

// ---------- Список профилей ----------

@Composable
fun RoutingSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val store = remember { RoutingProfileStore(context) }
    var profiles by remember { mutableStateOf(store.list()) }
    var message by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text("Маршрутизация", style = MaterialTheme.typography.titleLarge)
        Text(
            "Профили правил: свой набор у каждой подписки.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = {
            store.save(RoutingProfile.empty(""))
            profiles = store.list()
        }) { Text("Новый профиль") }
        if (profiles.isEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Профилей пока нет. Новый профиль создаёт правила по умолчанию.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 12.dp)) {
            items(profiles, key = { it.id }) { profile ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(profile.name)
                            Text(
                                "стратегия: ${profile.domainStrategy} · правил: " +
                                    rulesCount(profile),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(onClick = {
                            navController.navigate("routing_edit/${profile.id}")
                        }) { Text("Изменить") }
                    }
                }
            }
        }
    }
}

private fun rulesCount(p: RoutingProfile): Int =
    p.directSites.size + p.directIp.size + p.proxySites.size +
        p.proxyIp.size + p.blockSites.size + p.blockIp.size

// ---------- Редактор профиля ----------

@Composable
fun RoutingSettingsEditScreen(navController: NavController, profileId: String?) {
    val context = LocalContext.current
    val store = remember { RoutingProfileStore(context) }
    var profile by remember {
        mutableStateOf(
            profileId?.let { store.get(it) } ?: RoutingProfile.empty(""),
        )
    }
    var message by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text("Редактор маршрутизации", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = profile.name,
            onValueChange = { profile = profile.copy(name = it) },
            label = { Text("Имя профиля") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Весь трафик через прокси")
            Switch(
                checked = profile.globalProxy,
                onCheckedChange = { profile = profile.copy(globalProxy = it) },
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = profile.domainStrategy,
            onValueChange = { profile = profile.copy(domainStrategy = it) },
            label = { Text("Стратегия доменов (IPIfNonMatch / AsIs)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = profile.directSites.joinToString("\n"),
            onValueChange = {
                profile = profile.copy(directSites = it.lines().map { l -> l.trim() }.filter { l -> l.isNotEmpty() })
            },
            label = { Text("Прямые сайты (domain:example.com, geosite:ru)") },
            modifier = Modifier.fillMaxWidth().height(120.dp),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = profile.directIp.joinToString("\n"),
            onValueChange = {
                profile = profile.copy(directIp = it.lines().map { l -> l.trim() }.filter { l -> l.isNotEmpty() })
            },
            label = { Text("Прямые IP (geoip:ru, 10.0.0.0/8)") },
            modifier = Modifier.fillMaxWidth().height(120.dp),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = profile.blockSites.joinToString("\n"),
            onValueChange = {
                profile = profile.copy(blockSites = it.lines().map { l -> l.trim() }.filter { l -> l.isNotEmpty() })
            },
            label = { Text("Блокировать сайты") },
            modifier = Modifier.fillMaxWidth().height(80.dp),
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = {
            store.save(profile)
            message = "Сохранено"
        }) { Text("Сохранить") }
        if (profile.boundSubscriptionId() != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Профиль привязан к подписке.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        message?.let { Text(it) }
    }
}

private fun RoutingProfile.boundSubscriptionId(): String? = subscriptionId

// ---------- Правила (порядок) ----------

@Composable
fun RoutingRulesScreen(profileId: String?) {
    val context = LocalContext.current
    val store = remember { RoutingProfileStore(context) }
    val profile = profileId?.let { store.get(it) }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text("Правила маршрутизации", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        if (profile == null) {
            Text("Профиль не найден", color = MaterialTheme.colorScheme.error)
            return@Column
        }
        // Порядок фиксирован: block -> proxy -> direct (первое совпадение выигрывает).
        RuleBlock("Блокировать — сайты", profile.blockSites)
        RuleBlock("Блокировать — IP", profile.blockIp)
        if (!profile.globalProxy) {
            RuleBlock("Через прокси — сайты", profile.proxySites)
            RuleBlock("Через прокси — IP", profile.proxyIp)
        }
        RuleBlock("Прямо — сайты", profile.directSites)
        RuleBlock("Прямо — IP", profile.directIp)
    }
}

@Composable
private fun RuleBlock(title: String, values: List<String>) {
    if (values.isEmpty()) return
    Text(title, style = MaterialTheme.typography.titleMedium)
    values.forEach { value ->
        Text("• $value", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(8.dp))
}

// ---------- DNS-зоны профиля ----------

@Composable
fun RoutingProfileScreen(profileId: String?) {
    val context = LocalContext.current
    val store = remember { RoutingProfileStore(context) }
    val profile = profileId?.let { store.get(it) }
    var message by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(profile ?: RoutingProfile.empty("")) }

    if (profile == null) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            Text("Профиль не найден", color = MaterialTheme.colorScheme.error)
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text("DNS профиля", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = working.remoteDnsDomain,
            onValueChange = { working = working.copy(remoteDnsDomain = it) },
            label = { Text("Удалённый DNS (DoH-домен)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = working.remoteDnsIp,
            onValueChange = { working = working.copy(remoteDnsIp = it) },
            label = { Text("Удалённый DNS IP") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = working.domesticDnsDomain,
            onValueChange = { working = working.copy(domesticDnsDomain = it) },
            label = { Text("Домашний DNS (DoH-домен)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = working.domesticDnsIp,
            onValueChange = { working = working.copy(domesticDnsIp = it) },
            label = { Text("Домашний DNS IP") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = working.dnsHosts.entries.joinToString("\n") { "${it.key} ${it.value}" },
            onValueChange = { text ->
                val hosts = text.lines().mapNotNull { line ->
                    val parts = line.trim().split(Regex("[ ]+"))
                    if (parts.size == 2) parts[0] to parts[1] else null
                }.toMap()
                working = working.copy(dnsHosts = hosts)
            },
            label = { Text("DNS hosts (домен IP, по строкам)") },
            modifier = Modifier.fillMaxWidth().height(100.dp),
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = {
            store.save(working)
            message = "Сохранено"
        }) { Text("Сохранить") }
        message?.let { Text(it) }
    }
}

// ---------- Привязка профиля к подписке ----------

@Composable
fun SubRoutingScreen() {
    val context = LocalContext.current
    val store = remember { RoutingProfileStore(context) }
    val profiles = store.list()

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text("Привязка профиля к подписке", style = MaterialTheme.typography.titleLarge)
        Text(
            "Профиль с привязкой работает только для своей подписки.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        if (profiles.isEmpty()) {
            Text("Профилей нет — создайте их в разделе маршрутизации.")
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(profiles, key = { it.id }) { profile ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(profile.name)
                        Text(
                            profile.subscriptionId?.let { "подписка: $it" } ?: "общий профиль",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// ---------- Поиск по правилам ----------

@Composable
fun RoutingSettingsEditSearchScreen() {
    val context = LocalContext.current
    val store = remember { RoutingProfileStore(context) }
    var query by remember { mutableStateOf("") }
    val results = remember(query) {
        if (query.isBlank()) {
            emptyList()
        } else {
            store.list().flatMap { profile ->
                val needle = query.lowercase()
                val hits = (profile.directSites + profile.directIp +
                    profile.proxySites + profile.proxyIp +
                    profile.blockSites + profile.blockIp).filter { it.lowercase().contains(needle) }
                hits.map { it to profile.name }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text("Поиск по правилам", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Домен, IP или тег") },
            modifier = Modifier.fillMaxWidth(),
        )
        LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 12.dp)) {
            items(results, key = { it.first + "|" + it.second }) { (value, profileName) ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(value)
                        Text(
                            profileName,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
