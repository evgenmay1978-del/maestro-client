package com.maestrovpn.client.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.maestrovpn.client.R
import com.maestrovpn.client.core.settings.AdvancedConfig
import com.maestrovpn.client.core.settings.AdvancedSettings

/**
 * Продвинутые настройки (issue #3): DNS, порты inbound, MTU туннеля, Mux.
 *
 * Экран ничего не выдумывает: пишет через [AdvancedSettings] (тот же
 * Room-KeyValue, что и остальные настройки), а значения попадают в Xray-конфиг
 * при следующем подключении — [com.maestrovpn.client.core.subscription.XrayConfigBuilder.build]
 * получает их из AdvancedSettings.snapshot().
 *
 * Валидация до записи: кривое значение не сохраняется в БД вообще,
 * поэтому хранилище никогда не отдаёт движку мусор.
 */
@Composable
fun AdvancedSettingsScreen(navController: NavController) {
    val initial = remember { AdvancedSettings.snapshot() }

    var dnsPrimary by remember { mutableStateOf(initial.remoteDnsPrimary) }
    var dnsSecondary by remember { mutableStateOf(initial.remoteDnsSecondary) }
    var queryStrategy by remember { mutableStateOf(initial.queryStrategy) }
    var socksPort by remember { mutableStateOf(initial.socksPort.toString()) }
    var httpPort by remember { mutableStateOf(initial.httpPort.toString()) }
    var tunMtu by remember { mutableStateOf(initial.tunMtu.toString()) }
    var muxEnabled by remember { mutableStateOf(initial.muxEnabled) }
    var muxConcurrency by remember { mutableStateOf(initial.muxConcurrency.toString()) }
    var muxXudpConcurrency by remember { mutableStateOf(initial.muxXudpConcurrency.toString()) }
    var muxBehavior by remember { mutableStateOf(initial.muxXudpBehavior) }

    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val dnsError = stringResource(R.string.advanced_invalid_dns)
    val portError = stringResource(R.string.advanced_invalid_port)
    val mtuError = stringResource(R.string.advanced_invalid_mtu)
    val muxError = stringResource(R.string.advanced_invalid_mux)
    val portsEqualError = stringResource(R.string.advanced_ports_equal)
    val savedText = stringResource(R.string.advanced_saved)
    val resetText = stringResource(R.string.advanced_reset_done)

    fun reloadFromStore() {
        val s = AdvancedSettings.snapshot()
        dnsPrimary = s.remoteDnsPrimary
        dnsSecondary = s.remoteDnsSecondary
        queryStrategy = s.queryStrategy
        socksPort = s.socksPort.toString()
        httpPort = s.httpPort.toString()
        tunMtu = s.tunMtu.toString()
        muxEnabled = s.muxEnabled
        muxConcurrency = s.muxConcurrency.toString()
        muxXudpConcurrency = s.muxXudpConcurrency.toString()
        muxBehavior = s.muxXudpBehavior
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.advanced_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.advanced_applies),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ---------- DNS ----------
        SectionTitle(stringResource(R.string.advanced_dns_section))
        OutlinedTextField(
            value = dnsPrimary,
            onValueChange = { dnsPrimary = it },
            label = { Text(stringResource(R.string.advanced_dns_primary)) },
            supportingText = { Text(stringResource(R.string.advanced_dns_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = dnsSecondary,
            onValueChange = { dnsSecondary = it },
            label = { Text(stringResource(R.string.advanced_dns_secondary)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.advanced_query_strategy),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AdvancedConfig.QUERY_STRATEGIES.forEach { strategy ->
                FilterChip(
                    selected = queryStrategy == strategy,
                    onClick = { queryStrategy = strategy },
                    label = { Text(AdvancedConfig.queryStrategyLabel(strategy)) },
                )
            }
        }

        // ---------- Порты ----------
        SectionTitle(stringResource(R.string.advanced_ports_section))
        OutlinedTextField(
            value = socksPort,
            onValueChange = { socksPort = it },
            label = { Text(stringResource(R.string.advanced_socks_port)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = httpPort,
            onValueChange = { httpPort = it },
            label = { Text(stringResource(R.string.advanced_http_port)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        // ---------- TUN ----------
        SectionTitle(stringResource(R.string.advanced_tun_section))
        OutlinedTextField(
            value = tunMtu,
            onValueChange = { tunMtu = it },
            label = { Text(stringResource(R.string.advanced_tun_mtu)) },
            supportingText = { Text(stringResource(R.string.advanced_mtu_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        // ---------- Mux ----------
        SectionTitle(stringResource(R.string.advanced_mux_section))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.advanced_mux_enabled))
            Switch(checked = muxEnabled, onCheckedChange = { muxEnabled = it })
        }
        if (muxEnabled) {
            OutlinedTextField(
                value = muxConcurrency,
                onValueChange = { muxConcurrency = it },
                label = { Text(stringResource(R.string.advanced_mux_concurrency)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = muxXudpConcurrency,
                onValueChange = { muxXudpConcurrency = it },
                label = { Text(stringResource(R.string.advanced_mux_xudp_concurrency)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.advanced_mux_behavior),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AdvancedConfig.MUX_BEHAVIORS.forEach { behavior ->
                    FilterChip(
                        selected = muxBehavior == behavior,
                        onClick = { muxBehavior = behavior },
                        label = { Text(behavior) },
                    )
                }
            }
        }
        Text(
            stringResource(R.string.advanced_mux_note),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ---------- Действия ----------
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

        Button(
            onClick = {
                val reason = when {
                    !AdvancedConfig.isValidDnsServer(dnsPrimary) -> dnsError
                    !AdvancedConfig.isValidDnsServer(dnsSecondary) -> dnsError
                    !AdvancedConfig.isValidPort(socksPort) -> portError
                    !AdvancedConfig.isValidPort(httpPort) -> portError
                    socksPort.trim() == httpPort.trim() -> portsEqualError
                    !AdvancedConfig.isValidMtu(tunMtu) -> mtuError
                    muxEnabled && !AdvancedConfig.isValidMuxConcurrency(muxConcurrency) -> muxError
                    muxEnabled && !AdvancedConfig.isValidMuxConcurrency(muxXudpConcurrency) -> muxError
                    else -> null
                }
                if (reason != null) {
                    error = reason
                    message = null
                    return@Button
                }
                AdvancedSettings.remoteDnsPrimary = dnsPrimary
                AdvancedSettings.remoteDnsSecondary = dnsSecondary
                AdvancedSettings.queryStrategy = queryStrategy
                AdvancedSettings.socksPort = socksPort.trim().toInt()
                AdvancedSettings.httpPort = httpPort.trim().toInt()
                AdvancedSettings.tunMtu = tunMtu.trim().toInt()
                AdvancedSettings.muxEnabled = muxEnabled
                if (muxEnabled) {
                    AdvancedSettings.muxConcurrency = muxConcurrency.trim().toInt()
                    AdvancedSettings.muxXudpConcurrency = muxXudpConcurrency.trim().toInt()
                    AdvancedSettings.muxXudpBehavior = muxBehavior
                }
                reloadFromStore()
                error = null
                message = savedText
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.advanced_save)) }

        OutlinedButton(
            onClick = {
                AdvancedSettings.reset()
                reloadFromStore()
                error = null
                message = resetText
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.advanced_reset)) }

        Button(
            onClick = { navController.popBackStack() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.advanced_back)) }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}