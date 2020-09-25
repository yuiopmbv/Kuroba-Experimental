package com.github.k1rakishou.chan.features.setup

import android.content.Context
import com.github.k1rakishou.chan.Chan
import com.github.k1rakishou.chan.R
import com.github.k1rakishou.chan.core.base.BasePresenter
import com.github.k1rakishou.chan.core.manager.BoardManager
import com.github.k1rakishou.chan.core.manager.SiteManager
import com.github.k1rakishou.chan.core.settings.OptionSettingItem
import com.github.k1rakishou.chan.core.settings.Setting
import com.github.k1rakishou.chan.core.site.Site
import com.github.k1rakishou.chan.core.site.SiteSetting
import com.github.k1rakishou.chan.features.settings.*
import com.github.k1rakishou.chan.features.settings.setting.InputSettingV2
import com.github.k1rakishou.chan.features.settings.setting.LinkSettingV2
import com.github.k1rakishou.chan.features.settings.setting.ListSettingV2
import com.github.k1rakishou.chan.ui.controller.LoginController
import com.github.k1rakishou.model.data.descriptor.SiteDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class SiteSettingsPresenter : BasePresenter<SiteSettingsView>() {

  @Inject
  lateinit var siteManager: SiteManager
  @Inject
  lateinit var boardManager: BoardManager

  override fun onCreate(view: SiteSettingsView) {
    super.onCreate(view)

    Chan.inject(this)
  }

  suspend fun showSiteSettings(context: Context, siteDescriptor: SiteDescriptor): List<SettingsGroup> {
    return withContext(Dispatchers.Default) {
      siteManager.awaitUntilInitialized()
      boardManager.awaitUntilInitialized()

      val site = siteManager.bySiteDescriptor(siteDescriptor)
      if (site == null) {
        withView {
          val message = context.getString(R.string.site_settings_not_site_found, siteDescriptor.siteName)
          showErrorToast(message)
        }

        return@withContext emptyList()
      }

      val isSiteActive = siteManager.isSiteActive(siteDescriptor)
      if (!isSiteActive) {
        withView {
          val message = context.getString(R.string.site_settings_site_is_not_active)
          showErrorToast(message)
        }

        return@withContext emptyList()
      }

      val groups = collectGroupBuilders(context, site)
        .map { it.buildFunction.invoke() }

      groups.forEach { settingsGroup -> settingsGroup.rebuildSettings(BuildOptions.Default) }

      return@withContext groups
    }
  }

  private fun collectGroupBuilders(context: Context, site: Site): List<SettingsGroup.SettingsGroupBuilder> {
    val groups = mutableListOf<SettingsGroup.SettingsGroupBuilder>()
    groups += buildGeneralGroup(context, site.siteDescriptor())

    if (site.siteFeature(Site.SiteFeature.LOGIN)) {
      groups += buildAuthenticationGroup(context, site)
    }

    if (site.settings().isNotEmpty()) {
      groups += buildSiteSpecificSettingsGroup(context, site)
    }

    return groups
  }

  private fun buildSiteSpecificSettingsGroup(
    context: Context,
    site: Site
  ): SettingsGroup.SettingsGroupBuilder {
    return SettingsGroup.SettingsGroupBuilder(
      groupIdentifier = SiteSettingsScreen.AdditionalSettingsGroup,
      buildFunction = fun(): SettingsGroup {
        val group = SettingsGroup(
          groupTitle = "Additional settings",
          groupIdentifier = SiteSettingsScreen.AdditionalSettingsGroup
        )

        site.settings().forEach { siteSetting ->
          val settingId = SiteSettingsScreen.AdditionalSettingsGroup.getGroupIdentifier().id + "_" + siteSetting.name
          val identifier = SiteSettingsScreen.AdditionalSettingsGroup(settingId)

          when (siteSetting) {
            is SiteSetting.SiteOptionsSetting -> {
              group += ListSettingV2.createBuilder(
                context = context,
                identifier = identifier,
                setting = siteSetting.options as Setting<OptionSettingItem>,
                items = siteSetting.options.items.toList(),
                itemNameMapper = { item -> item.key },
                topDescriptionStringFunc = { siteSetting.name },
                bottomDescriptionStringFunc = { siteSetting.options.get().name }
              )
            }
            is SiteSetting.SiteStringSetting -> {
              group += InputSettingV2.createBuilder(
                context = context,
                identifier = identifier,
                setting = siteSetting.setting,
                inputType = InputSettingV2.InputType.String,
                topDescriptionStringFunc = { siteSetting.name },
                bottomDescriptionStringFunc = { siteSetting.setting.get() }
              )
            }
          }
        }

        return group
      }
    )
  }

  private fun buildAuthenticationGroup(
    context: Context,
    site: Site
  ): SettingsGroup.SettingsGroupBuilder {
    return SettingsGroup.SettingsGroupBuilder(
      groupIdentifier = SiteSettingsScreen.AuthenticationGroup,
      buildFunction = fun(): SettingsGroup {
        val group = SettingsGroup(
          groupTitle = "Authentication",
          groupIdentifier = SiteSettingsScreen.AuthenticationGroup
        )

        group += LinkSettingV2.createBuilder(
          context = context,
          identifier = SiteSettingsScreen.AuthenticationGroup.Login,
          topDescriptionStringFunc = { "Login" },
          bottomDescriptionStringFunc = {
            if (site.actions().isLoggedIn()) {
              "On"
            } else {
              "Off"
            }
          },
          callback = {
            withViewNormal {
              openControllerWrappedIntoBottomNavAwareController(LoginController(context, site))
            }
          }
        )

        return group
      }
    )
  }

  private fun buildGeneralGroup(
    context: Context,
    siteDescriptor: SiteDescriptor
  ): SettingsGroup.SettingsGroupBuilder {
    return SettingsGroup.SettingsGroupBuilder(
      groupIdentifier = SiteSettingsScreen.GeneralGroup,
      buildFunction = fun(): SettingsGroup {
        val group = SettingsGroup(
          groupTitle = "General",
          groupIdentifier = SiteSettingsScreen.GeneralGroup
        )

        group += LinkSettingV2.createBuilder(
          context = context,
          identifier = SiteSettingsScreen.GeneralGroup.SetUpBoards,
          topDescriptionStringFunc = { "Set up boards" },
          bottomDescriptionStringFunc = { "${boardManager.activeBoardsCount(siteDescriptor)} board(s) added" },
          callback = {
            withViewNormal { pushController(BoardsSetupController(context, siteDescriptor)) }
          }
        )

        return group
      }
    )
  }

  sealed class SiteSettingsScreen(
    groupIdentifier: GroupIdentifier,
    settingsIdentifier: SettingIdentifier,
    screenIdentifier: ScreenIdentifier = SiteSettingsScreen.getScreenIdentifier()
  ) : IScreen,
    SettingsIdentifier(screenIdentifier, groupIdentifier, settingsIdentifier) {

    sealed class GeneralGroup(
      settingsId: String,
      groupIdentifier: GroupIdentifier = GeneralGroup.getGroupIdentifier()
    ) : IGroup,
      SiteSettingsScreen(groupIdentifier, SettingIdentifier(settingsId)) {

      object SetUpBoards : GeneralGroup("set_up_boards")

      companion object : IGroupIdentifier() {
        override fun getScreenIdentifier(): ScreenIdentifier = SiteSettingsScreen.getScreenIdentifier()
        override fun getGroupIdentifier(): GroupIdentifier = GroupIdentifier("general_group")
      }
    }

    sealed class AuthenticationGroup(
      settingsId: String,
      groupIdentifier: GroupIdentifier = AuthenticationGroup.getGroupIdentifier()
    ) : IGroup,
      SiteSettingsScreen(groupIdentifier, SettingIdentifier(settingsId)) {

      object Login : AuthenticationGroup("login")

      companion object : IGroupIdentifier() {
        override fun getScreenIdentifier(): ScreenIdentifier = SiteSettingsScreen.getScreenIdentifier()
        override fun getGroupIdentifier(): GroupIdentifier = GroupIdentifier("authentication_group")
      }
    }

    class AdditionalSettingsGroup(
      settingsId: String,
      groupIdentifier: GroupIdentifier = AuthenticationGroup.getGroupIdentifier()
    ) : IGroup,
      SiteSettingsScreen(groupIdentifier, SettingIdentifier(settingsId)) {

      companion object : IGroupIdentifier() {
        override fun getScreenIdentifier(): ScreenIdentifier = SiteSettingsScreen.getScreenIdentifier()
        override fun getGroupIdentifier(): GroupIdentifier = GroupIdentifier("additional_settings_group")
      }
    }

    companion object : IScreenIdentifier() {
      override fun getScreenIdentifier(): ScreenIdentifier = ScreenIdentifier("developer_settings_screen")
    }
  }

  companion object {
    private const val TAG = "SiteSettingsPresenter"
  }

}