package org.wordpress.android.ui.bloggingreminders

import org.wordpress.android.R
import org.wordpress.android.fluxc.model.BloggingRemindersModel
import org.wordpress.android.fluxc.model.BloggingRemindersModel.Day
import org.wordpress.android.ui.bloggingreminders.BloggingRemindersItem.DayButtons
import org.wordpress.android.ui.bloggingreminders.BloggingRemindersItem.DayButtons.DayItem
import org.wordpress.android.ui.bloggingreminders.BloggingRemindersItem.Illustration
import org.wordpress.android.ui.bloggingreminders.BloggingRemindersItem.MediumEmphasisText
import org.wordpress.android.ui.bloggingreminders.BloggingRemindersItem.PrimaryButton
import org.wordpress.android.ui.bloggingreminders.BloggingRemindersItem.Title
import org.wordpress.android.ui.utils.ListItemInteraction
import org.wordpress.android.ui.utils.UiString.UiStringRes
import org.wordpress.android.ui.utils.UiString.UiStringText
import javax.inject.Inject

class DaySelectionBuilder
@Inject constructor(private val daysProvider: DaysProvider) {
    fun buildSelection(
        bloggingRemindersModel: BloggingRemindersModel?,
        onSelectDay: (Day) -> Unit,
        onConfirm: (BloggingRemindersModel?) -> Unit
    ): List<BloggingRemindersItem> {
        val daysOfWeek = daysProvider.getDays()
        return listOf(
                Illustration(R.drawable.img_illustration_calendar),
                Title(UiStringRes(R.string.blogging_reminders_select_days)),
                MediumEmphasisText(UiStringRes(R.string.blogging_reminders_select_days_message)),
                DayButtons(daysOfWeek.map {
                    DayItem(
                            UiStringText(it.first),
                            bloggingRemindersModel?.enabledDays?.contains(it.second) == true,
                            ListItemInteraction.create(it.second, onSelectDay)
                    )
                }),
                PrimaryButton(
                        UiStringRes(R.string.blogging_reminders_notify_me),
                        enabled = bloggingRemindersModel?.enabledDays?.isNotEmpty() == true,
                        ListItemInteraction.create(bloggingRemindersModel, onConfirm)
                )
        )
    }
}
