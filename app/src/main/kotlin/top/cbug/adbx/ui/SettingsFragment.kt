package top.cbug.adbx.ui

import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import top.cbug.adbx.MainActivity
import top.cbug.adbx.R
import top.cbug.adbx.store.Settings as AppSettings

/**
 * Settings tab — automation switches + language picker (popup) + about card.
 */
class SettingsFragment : Fragment() {

    private lateinit var swAutoEnable: MaterialSwitch
    private lateinit var swBootStart: MaterialSwitch
    private lateinit var cardLanguage: MaterialCardView
    private lateinit var tvLanguageSub: TextView
    private lateinit var tvAboutVersion: TextView
    private lateinit var tvAboutModuleId: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        swAutoEnable    = view.findViewById(R.id.swAutoEnable)
        swBootStart     = view.findViewById(R.id.swBootStart)
        cardLanguage    = view.findViewById(R.id.cardLanguage)
        tvLanguageSub   = view.findViewById(R.id.tvLanguageSub)
        tvAboutVersion  = view.findViewById(R.id.tvAboutVersion)
        tvAboutModuleId = view.findViewById(R.id.tvAboutModuleId)

        AppSettings.load(requireContext())
        swAutoEnable.isChecked = AppSettings.autoEnable
        swBootStart.isChecked = AppSettings.bootStart

        swAutoEnable.setOnCheckedChangeListener { _, c ->
            AppSettings.autoEnable = c
            AppSettings.save(requireContext())
        }
        swBootStart.setOnCheckedChangeListener { _, c ->
            AppSettings.bootStart = c
            AppSettings.save(requireContext())
        }

        renderLanguageLabel()
        cardLanguage.setOnClickListener { showLanguageDialog() }

        // About
        try {
            val info = requireContext().packageManager
                .getPackageInfo(requireContext().packageName, 0)
            val version = info.versionName ?: "?"
            tvAboutVersion.text = getString(R.string.about_version) + ": " + version
        } catch (_: PackageManager.NameNotFoundException) {
            tvAboutVersion.text = getString(R.string.about_version) + ": ?"
        }
        tvAboutModuleId.text = getString(R.string.about_module_id) + ": " +
            requireContext().packageName
    }

    override fun onResume() {
        super.onResume()
        AppSettings.load(requireContext())
        renderLanguageLabel()
    }

    private fun renderLanguageLabel() {
        tvLanguageSub.text = when (AppSettings.locale) {
            "en" -> getString(R.string.lang_english)
            "zh" -> getString(R.string.lang_chinese)
            else -> getString(R.string.lang_system)
        }
    }

    private fun showLanguageDialog() {
        val labels = arrayOf(
            getString(R.string.lang_system),
            getString(R.string.lang_english),
            getString(R.string.lang_chinese),
        )
        val tags = arrayOf("system", "en", "zh")
        val currentIndex = tags.indexOf(AppSettings.locale).coerceAtLeast(0)

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.section_language)
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                val newLocale = tags[which]
                if (newLocale != AppSettings.locale) {
                    AppSettings.locale = newLocale
                    AppSettings.save(requireContext())
                    (activity as? MainActivity)?.recreate()
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}