package com.chasel.ng2n.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chasel.ng2n.data.ai.settings.AiKeyState
import com.chasel.ng2n.data.ai.settings.AiKeyStore
import com.chasel.ng2n.data.ai.settings.AiSettings
import com.chasel.ng2n.data.ai.settings.AiSettingsStore
import com.chasel.ng2n.data.ai.settings.AnalysisAllowance
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AiSettingsUiState(
  val usage: com.chasel.ng2n.core.ai.AiBudgetBook = com.chasel.ng2n.core.ai.AiBudgetBook(),
  val settings: AiSettings = AiSettings(),
  val key: AiKeyState = AiKeyState.Missing,
  val loaded: Boolean = false,
  val saving: Boolean = false,
  val error: String? = null,
)

@HiltViewModel
class AiSettingsViewModel @Inject constructor(
  private val settingsStore: AiSettingsStore,
  private val keyStore: AiKeyStore,
  private val budgets: com.chasel.ng2n.data.ai.AiBudgetStore,
) : ViewModel() {
  private val mutableState = MutableStateFlow(AiSettingsUiState())
  val state = mutableState.asStateFlow()

  init {
    viewModelScope.launch {
      try {
        budgets.initialize()
        budgets.books.collect { mutableState.value = mutableState.value.copy(usage = it) }
      } catch (e: CancellationException) { throw e }
      catch (_: Exception) { mutableState.value = mutableState.value.copy(error = "用量读取失败，不能按零用量处理") }
    }
    viewModelScope.launch {
      try {
        settingsStore.settings.collect { settings ->
          mutableState.value = mutableState.value.copy(settings = settings, loaded = true)
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        mutableState.value = mutableState.value.copy(error = "设置读取失败，请返回后重试")
      }
    }
    viewModelScope.launch {
      keyStore.state.collect { key -> mutableState.value = mutableState.value.copy(key = key) }
    }
  }

  fun saveKey(value: String, onSaved: () -> Unit) = write(onSaved) { keyStore.saveKey(value) }
  fun setAllowance(value: AnalysisAllowance, onSaved: () -> Unit) =
    write(onSaved) { settingsStore.setAllowance(value) }
  fun setDailyEnabled(enabled: Boolean) = write { settingsStore.setDailyEnabled(enabled) }
  fun setDailyLimit(cents: Long, onSaved: () -> Unit) =
    write(onSaved) { settingsStore.setDailyLimit(cents) }

  private fun write(onSaved: () -> Unit = {}, action: suspend () -> Unit) {
    if (mutableState.value.saving) return
    mutableState.value = mutableState.value.copy(saving = true, error = null)
    viewModelScope.launch {
      try {
        action()
        onSaved()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        mutableState.value = mutableState.value.copy(error = "保存失败，原有配置已保留，请重试")
      } finally {
        mutableState.value = mutableState.value.copy(saving = false)
      }
    }
  }
}
