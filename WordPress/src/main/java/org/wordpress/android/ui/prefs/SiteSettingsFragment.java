package org.wordpress.android.ui.prefs;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.util.SparseBooleanArray;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.android.volley.VolleyError;
import com.wordpress.rest.RestRequest;

import org.json.JSONObject;
import org.wordpress.android.R;
import org.wordpress.android.WordPress;
import org.wordpress.android.analytics.AnalyticsTracker;
import org.wordpress.android.models.AccountHelper;
import org.wordpress.android.models.Blog;
import org.wordpress.android.ui.ActivityLauncher;
import org.wordpress.android.ui.main.WPMainActivity;
import org.wordpress.android.ui.stats.StatsWidgetProvider;
import org.wordpress.android.ui.stats.datasets.StatsTable;
import org.wordpress.android.util.AnalyticsUtils;
import org.wordpress.android.util.CoreEvents;
import org.wordpress.android.util.HelpshiftHelper;
import org.wordpress.android.util.NetworkUtils;
import org.wordpress.android.util.StringUtils;
import org.wordpress.android.util.ToastUtils;
import org.wordpress.android.util.WPActivityUtils;
import org.wordpress.android.util.WPPrefUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import de.greenrobot.event.EventBus;

/**
 * Allows interfacing with WordPress site settings. Works with WP.com and WP.org v4.5+ (pending).
 *
 * Settings are synced automatically when local changes are made.
 */

public class SiteSettingsFragment extends PreferenceFragment
        implements Preference.OnPreferenceChangeListener,
                   Preference.OnPreferenceClickListener,
                   AdapterView.OnItemLongClickListener,
                   ViewGroup.OnHierarchyChangeListener,
                   Dialog.OnDismissListener,
                   SiteSettingsInterface.SiteSettingsListener {

    /**
     * Use this argument to pass the {@link Integer} local blog ID to this fragment.
     */
    public static final String ARG_LOCAL_BLOG_ID = "local_blog_id";

    /**
     * When the user removes a site (by selecting Delete Site) the parent {@link Activity} result
     * is set to this value and {@link Activity#finish()} is invoked.
     */
    public static final int RESULT_BLOG_REMOVED = Activity.RESULT_FIRST_USER;

    /**
     * Provides the regex to identify domain HTTP(S) protocol and/or 'www' sub-domain.
     *
     * Used to format user-facing {@link String}'s in certain preferences.
     */
    private static final String ADDRESS_FORMAT_REGEX = "^(https?://(w{3})?|www\\.)";

    /**
     * Used to move the Uncategorized category to the beginning of the category list.
     */
    private static final int UNCATEGORIZED_CATEGORY_ID = 1;

    /**
     * Request code used when creating the {@link RelatedPostsDialog}.
     */
    private static final int RELATED_POSTS_REQUEST_CODE = 1;
    private static final int THREADING_REQUEST_CODE = 2;
    private static final int PAGING_REQUEST_CODE = 3;
    private static final int CLOSE_AFTER_REQUEST_CODE = 4;
    private static final int MULTIPLE_LINKS_REQUEST_CODE = 5;
    private static final int DELETE_SITE_REQUEST_CODE = 6;

    private static final long FETCH_DELAY = 1000;

    // Reference to blog obtained from passed ID (ARG_LOCAL_BLOG_ID)
    private Blog mBlog;

    // Can interface with WP.com or WP.org
    private SiteSettingsInterface mSiteSettings;

    // Reference to the list of items being edited in the current list editor
    private List<String> mEditingList;

    // Used to ensure that settings are only fetched once throughout the lifecycle of the fragment
    private boolean mShouldFetch;

    // General settings
    private EditTextPreference mTitlePref;
    private EditTextPreference mTaglinePref;
    private EditTextPreference mAddressPref;
    private DetailListPreference mPrivacyPref;
    private DetailListPreference mLanguagePref;

    // Account settings (NOTE: only for WP.org)
    private EditTextPreference mUsernamePref;
    private EditTextPreference mPasswordPref;

    // Writing settings
    private WPSwitchPreference mLocationPref;
    private DetailListPreference mCategoryPref;
    private DetailListPreference mFormatPref;
    private Preference mRelatedPostsPref;

    // Discussion settings preview
    private WPSwitchPreference mAllowCommentsPref;
    private WPSwitchPreference mSendPingbacksPref;
    private WPSwitchPreference mReceivePingbacksPref;

    // Discussion settings -> Defaults for New Posts
    private WPSwitchPreference mAllowCommentsNested;
    private WPSwitchPreference mSendPingbacksNested;
    private WPSwitchPreference mReceivePingbacksNested;

    // Discussion settings -> Comments
    private WPSwitchPreference mIdentityRequiredPreference;
    private WPSwitchPreference mUserAccountRequiredPref;
    private Preference mCloseAfterPref;
    private DetailListPreference mSortByPref;
    private DetailListPreference mThreadingPref;
    private Preference mPagingPref;
    private DetailListPreference mWhitelistPref;
    private Preference mMultipleLinksPref;
    private Preference mModerationHoldPref;
    private Preference mBlacklistPref;

    // This Device settings
    private DetailListPreference mImageWidthPref;
    private WPSwitchPreference mUploadAndLinkPref;

    // Advanced settings
    private Preference mStartOverPref;
    private Preference mExportSitePref;
    private Preference mDeleteSitePref;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Activity activity = getActivity();

        // make sure we have local site data and a network connection, otherwise finish activity
        mBlog = WordPress.getBlog(getArguments().getInt(ARG_LOCAL_BLOG_ID, -1));
        if (mBlog == null || !NetworkUtils.checkConnection(activity)) {
            getActivity().finish();
            return;
        }

        // track successful settings screen access
        AnalyticsUtils.trackWithCurrentBlogDetails(
                AnalyticsTracker.Stat.SITE_SETTINGS_ACCESSED);

        // setup state to fetch remote settings
        mShouldFetch = true;

        // initialize the appropriate settings interface (WP.com or WP.org)
        mSiteSettings = SiteSettingsInterface.getInterface(activity, mBlog, this);

        setRetainInstance(true);
        addPreferencesFromResource(R.xml.site_settings);

        // toggle which preferences are shown and set references
        initPreferences();
    }

    @Override
    public void onPause() {
        super.onPause();
        WordPress.wpDB.saveBlog(mBlog);
    }

    @Override
    public void onResume() {
        super.onResume();

        // always load cached settings
        mSiteSettings.init(false);

        if (mShouldFetch) {
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    // initialize settings with locally cached values, fetch remote on first pass
                    mSiteSettings.init(true);
                }
            }, FETCH_DELAY);
            // stop future calls from fetching remote settings
            mShouldFetch = false;
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case RELATED_POSTS_REQUEST_CODE:
                // data is null if user cancelled editing Related Posts settings
                if (data == null) break;
                mSiteSettings.setShowRelatedPosts(data.getBooleanExtra(
                        RelatedPostsDialog.SHOW_RELATED_POSTS_KEY, false));
                mSiteSettings.setShowRelatedPostHeader(data.getBooleanExtra(
                        RelatedPostsDialog.SHOW_HEADER_KEY, false));
                mSiteSettings.setShowRelatedPostImages(data.getBooleanExtra(
                        RelatedPostsDialog.SHOW_IMAGES_KEY, false));
                mSiteSettings.saveSettings();
                break;
            case THREADING_REQUEST_CODE:
                if (data == null) break;
                mSiteSettings.setShouldThreadComments(data.getBooleanExtra
                        (NumberPickerDialog.SWITCH_ENABLED_KEY, false));
                onPreferenceChange(mThreadingPref, data.getIntExtra(
                        NumberPickerDialog.CUR_VALUE_KEY, -1));
                break;
            case PAGING_REQUEST_CODE:
                if (data == null) break;
                mSiteSettings.setShouldPageComments(data.getBooleanExtra
                        (NumberPickerDialog.SWITCH_ENABLED_KEY, false));
                onPreferenceChange(mPagingPref, data.getIntExtra(
                        NumberPickerDialog.CUR_VALUE_KEY, -1));
                break;
            case CLOSE_AFTER_REQUEST_CODE:
                if (data == null) break;
                mSiteSettings.setShouldCloseAfter(data.getBooleanExtra
                        (NumberPickerDialog.SWITCH_ENABLED_KEY, false));
                onPreferenceChange(mCloseAfterPref, data.getIntExtra(
                        NumberPickerDialog.CUR_VALUE_KEY, -1));
                break;
            case MULTIPLE_LINKS_REQUEST_CODE:
                if (data == null) break;
                int numLinks = data.getIntExtra(NumberPickerDialog.CUR_VALUE_KEY, -1);
                if (numLinks < 0 || numLinks == mSiteSettings.getMultipleLinks()) return;
                onPreferenceChange(mMultipleLinksPref, numLinks);
                break;
            case DELETE_SITE_REQUEST_CODE:
                deleteSite();
                break;
        }

        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container,
                             Bundle savedInstanceState) {
        // use a wrapper to apply the Calypso theme
        Context themer = new ContextThemeWrapper(getActivity(), R.style.Calypso_SiteSettingsTheme);
        LayoutInflater localInflater = inflater.cloneInContext(themer);
        View view = super.onCreateView(localInflater, container, savedInstanceState);

        if (view != null) {
            setupPreferenceList((ListView) view.findViewById(android.R.id.list), getResources());
        }

        return view;
    }

    @Override
    public void onChildViewAdded(View parent, View child) {
        if (child.getId() == android.R.id.title && child instanceof TextView) {
            // style preference category title views
            TextView title = (TextView) child;
            WPPrefUtils.layoutAsBody2(title);
        } else {
            // style preference title views
            TextView title = (TextView) child.findViewById(android.R.id.title);
            if (title != null) WPPrefUtils.layoutAsSubhead(title);
        }
    }

    @Override
    public void onChildViewRemoved(View parent, View child) {
        // NOP
    }

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen screen, Preference preference) {
        super.onPreferenceTreeClick(screen, preference);

        // More preference selected, style the Discussion screen
        if (preference == findPreference(getString(R.string.pref_key_site_more_discussion))) {
            Dialog dialog = ((PreferenceScreen) preference).getDialog();
            if (dialog == null) return false;

            setupPreferenceList((ListView) dialog.findViewById(android.R.id.list), getResources());

            // add Action Bar
            String title = getString(R.string.site_settings_discussion_title);
            WPActivityUtils.addToolbarToDialog(this, dialog, title);

            // track user accessing the full Discussion settings screen
            AnalyticsUtils.trackWithCurrentBlogDetails(
                    AnalyticsTracker.Stat.SITE_SETTINGS_ACCESSED_MORE_SETTINGS);
        } else if (preference == findPreference(getString(R.string.pref_key_site_delete_site_screen))) {
            Dialog dialog = ((PreferenceScreen) preference).getDialog();
            if (dialog == null) return false;

            String title = getString(R.string.site_settings_delete_site_title);
            WPActivityUtils.addToolbarToDialog(this, dialog, title);
        }

        return false;
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        if (preference == mRelatedPostsPref) {
            showRelatedPostsDialog();
            return true;
        } else if (preference == mMultipleLinksPref) {
            showMultipleLinksDialog();
            return true;
        } else if (preference == mModerationHoldPref) {
            mEditingList = mSiteSettings.getModerationKeys();
            showListEditorDialog(R.string.site_settings_moderation_hold_title,
                    R.string.site_settings_hold_for_moderation_description);
            return true;
        } else if (preference == mBlacklistPref) {
            mEditingList = mSiteSettings.getBlacklistKeys();
            showListEditorDialog(R.string.site_settings_blacklist_title,
                    R.string.site_settings_blacklist_description);
            return true;
        } else if (preference == mStartOverPref) {
            showStartOverDialog();
            return true;
        } else if (preference == mCloseAfterPref) {
            showCloseAfterDialog();
            return true;
        } else if (preference == mPagingPref) {
            showPagingDialog();
            return true;
        } else if (preference == mCategoryPref || preference == mFormatPref) {
            return !shouldShowListPreference((DetailListPreference) preference);
        } else if (preference == mExportSitePref) {
            showExportContentDialog();
            return true;
        } else if (preference == mDeleteSitePref) {
            showDeleteSiteDialog();
            return true;
        }

        return false;
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (newValue == null) return false;

        if (preference == mTitlePref) {
            mSiteSettings.setTitle(newValue.toString());
            changeEditTextPreferenceValue(mTitlePref, mSiteSettings.getTitle());
        } else if (preference == mTaglinePref) {
            mSiteSettings.setTagline(newValue.toString());
            changeEditTextPreferenceValue(mTaglinePref, mSiteSettings.getTagline());
        } else if (preference == mAddressPref) {
            mSiteSettings.setAddress(newValue.toString());
            changeEditTextPreferenceValue(mAddressPref, mSiteSettings.getAddress());
        } else if (preference == mLanguagePref) {
            mSiteSettings.setLanguageCode(newValue.toString());
            changeLanguageValue(mSiteSettings.getLanguageCode());
        } else if (preference == mPrivacyPref) {
            mSiteSettings.setPrivacy(Integer.valueOf(newValue.toString()));
            setDetailListPreferenceValue(mPrivacyPref,
                    String.valueOf(mSiteSettings.getPrivacy()),
                    mSiteSettings.getPrivacyDescription());
        } else if (preference == mAllowCommentsPref || preference == mAllowCommentsNested) {
            setAllowComments((Boolean) newValue);
        } else if (preference == mSendPingbacksPref || preference == mSendPingbacksNested) {
            setSendPingbacks((Boolean) newValue);
        } else if (preference == mReceivePingbacksPref || preference == mReceivePingbacksNested) {
            setReceivePingbacks((Boolean) newValue);
        } else if (preference == mCloseAfterPref) {
            mSiteSettings.setCloseAfter(Integer.parseInt(newValue.toString()));
            if (mSiteSettings.getShouldCloseAfter()) {
                mCloseAfterPref.setSummary(mSiteSettings.getCloseAfterDescription());
            } else {
                mCloseAfterPref.setSummary(mSiteSettings.getCloseAfterDescription(0));
            }
        } else if (preference == mSortByPref) {
            mSiteSettings.setCommentSorting(Integer.parseInt(newValue.toString()));
            setDetailListPreferenceValue(mSortByPref,
                    newValue.toString(),
                    mSiteSettings.getSortingDescription());
        } else if (preference == mThreadingPref) {
            mSiteSettings.setThreadingLevels(Integer.parseInt(newValue.toString()));
            setDetailListPreferenceValue(mThreadingPref,
                    newValue.toString(),
                    mSiteSettings.getThreadingDescription());
        } else if (preference == mPagingPref) {
            mSiteSettings.setPagingCount(Integer.parseInt(newValue.toString()));
            mPagingPref.setSummary(mSiteSettings.getPagingDescription());
        } else if (preference == mIdentityRequiredPreference) {
            mSiteSettings.setIdentityRequired((Boolean) newValue);
        } else if (preference == mUserAccountRequiredPref) {
            mSiteSettings.setUserAccountRequired((Boolean) newValue);
        } else if (preference == mWhitelistPref) {
            updateWhitelistSettings(Integer.parseInt(newValue.toString()));
        } else if (preference == mMultipleLinksPref) {
            mSiteSettings.setMultipleLinks(Integer.parseInt(newValue.toString()));
            mMultipleLinksPref.setSummary(getResources()
                    .getQuantityString(R.plurals.site_settings_multiple_links_summary,
                            mSiteSettings.getMultipleLinks(),
                            mSiteSettings.getMultipleLinks()));
        } else if (preference == mUsernamePref) {
            mSiteSettings.setUsername(newValue.toString());
            changeEditTextPreferenceValue(mUsernamePref, mSiteSettings.getUsername());
        } else if (preference == mPasswordPref) {
            mSiteSettings.setPassword(newValue.toString());
            changeEditTextPreferenceValue(mPasswordPref, mSiteSettings.getPassword());
        } else if (preference == mLocationPref) {
            mSiteSettings.setLocation((Boolean) newValue);
        } else if (preference == mCategoryPref) {
            mSiteSettings.setDefaultCategory(Integer.parseInt(newValue.toString()));
            setDetailListPreferenceValue(mCategoryPref,
                    newValue.toString(),
                    mSiteSettings.getDefaultCategoryForDisplay());
        } else if (preference == mFormatPref) {
            mSiteSettings.setDefaultFormat(newValue.toString());
            setDetailListPreferenceValue(mFormatPref,
                    newValue.toString(),
                    mSiteSettings.getDefaultPostFormatDisplay());
        } else if (preference == mImageWidthPref) {
            mBlog.setMaxImageWidth(newValue.toString());
            setDetailListPreferenceValue(mImageWidthPref,
                    mBlog.getMaxImageWidth(),
                    mBlog.getMaxImageWidth());
        } else if (preference == mUploadAndLinkPref) {
            mBlog.setFullSizeImage(Boolean.valueOf(newValue.toString()));
        } else {
            return false;
        }

        mSiteSettings.saveSettings();

        return true;
    }

    @Override
    public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
        ListView listView = (ListView) parent;
        ListAdapter listAdapter = listView.getAdapter();
        Object obj = listAdapter.getItem(position);

        if (obj != null) {
            if (obj instanceof View.OnLongClickListener) {
                View.OnLongClickListener longListener = (View.OnLongClickListener) obj;
                return longListener.onLongClick(view);
            } else if (obj instanceof PreferenceHint) {
                PreferenceHint hintObj = (PreferenceHint) obj;
                if (hintObj.hasHint()) {
                    HashMap<String, Object> properties = new HashMap<>();
                    properties.put("hint_shown", hintObj.getHint());
                    AnalyticsUtils.trackWithCurrentBlogDetails(
                            AnalyticsTracker.Stat.SITE_SETTINGS_HINT_TOAST_SHOWN, properties);
                    ToastUtils.showToast(getActivity(), hintObj.getHint(), ToastUtils.Duration.SHORT);
                }
                return true;
            }
        }

        return false;
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        mSiteSettings.saveSettings();
        mEditingList = null;
    }

    @Override
    public void onSettingsUpdated(Exception error) {
        if (error != null) {
            ToastUtils.showToast(getActivity(), R.string.error_fetch_remote_site_settings);
            getActivity().finish();
            return;
        }

        if (isAdded()) setPreferencesFromSiteSettings();
    }

    @Override
    public void onSettingsSaved(Exception error) {
        if (error != null) {
            ToastUtils.showToast(WordPress.getContext(), R.string.error_post_remote_site_settings);
            return;
        }
        mBlog.setBlogName(mSiteSettings.getTitle());
        WordPress.wpDB.saveBlog(mBlog);
        EventBus.getDefault().post(new CoreEvents.BlogListChanged());
    }

    @Override
    public void onCredentialsValidated(Exception error) {
        if (error != null) {
            ToastUtils.showToast(WordPress.getContext(), R.string.username_or_password_incorrect);
        }
    }

    private void setupPreferenceList(ListView prefList, Resources res) {
        if (prefList == null || res == null) return;

        // customize list dividers
        //noinspection deprecation
        prefList.setDivider(res.getDrawable(R.drawable.preferences_divider));
        prefList.setDividerHeight(res.getDimensionPixelSize(R.dimen.site_settings_divider_height));
        // handle long clicks on preferences to display hints
        prefList.setOnItemLongClickListener(this);
        // required to customize (Calypso) preference views
        prefList.setOnHierarchyChangeListener(this);
        // remove footer divider bar
        prefList.setFooterDividersEnabled(false);
        //noinspection deprecation
        prefList.setOverscrollFooter(res.getDrawable(R.color.transparent));
    }

    /**
     * Helper method to retrieve {@link Preference} references and initialize any data.
     */
    private void initPreferences() {
        mTitlePref = (EditTextPreference) getChangePref(R.string.pref_key_site_title);
        mTaglinePref = (EditTextPreference) getChangePref(R.string.pref_key_site_tagline);
        mAddressPref = (EditTextPreference) getChangePref(R.string.pref_key_site_address);
        mPrivacyPref = (DetailListPreference) getChangePref(R.string.pref_key_site_visibility);
        mLanguagePref = (DetailListPreference) getChangePref(R.string.pref_key_site_language);
        mUsernamePref = (EditTextPreference) getChangePref(R.string.pref_key_site_username);
        mPasswordPref = (EditTextPreference) getChangePref(R.string.pref_key_site_password);
        mLocationPref = (WPSwitchPreference) getChangePref(R.string.pref_key_site_location);
        mCategoryPref = (DetailListPreference) getChangePref(R.string.pref_key_site_category);
        mFormatPref = (DetailListPreference) getChangePref(R.string.pref_key_site_format);
        mAllowCommentsPref = (WPSwitchPreference) getChangePref(R.string.pref_key_site_allow_comments);
        mAllowCommentsNested = (WPSwitchPreference) getChangePref(R.string.pref_key_site_allow_comments_nested);
        mSendPingbacksPref = (WPSwitchPreference) getChangePref(R.string.pref_key_site_send_pingbacks);
        mSendPingbacksNested = (WPSwitchPreference) getChangePref(R.string.pref_key_site_send_pingbacks_nested);
        mReceivePingbacksPref = (WPSwitchPreference) getChangePref(R.string.pref_key_site_receive_pingbacks);
        mReceivePingbacksNested = (WPSwitchPreference) getChangePref(R.string.pref_key_site_receive_pingbacks_nested);
        mIdentityRequiredPreference = (WPSwitchPreference) getChangePref(R.string.pref_key_site_identity_required);
        mUserAccountRequiredPref = (WPSwitchPreference) getChangePref(R.string.pref_key_site_user_account_required);
        mSortByPref = (DetailListPreference) getChangePref(R.string.pref_key_site_sort_by);
        mThreadingPref = (DetailListPreference) getChangePref(R.string.pref_key_site_threading);
        mWhitelistPref = (DetailListPreference) getChangePref(R.string.pref_key_site_whitelist);
        mRelatedPostsPref = getClickPref(R.string.pref_key_site_related_posts);
        mCloseAfterPref = getClickPref(R.string.pref_key_site_close_after);
        mPagingPref = getClickPref(R.string.pref_key_site_paging);
        mMultipleLinksPref = getClickPref(R.string.pref_key_site_multiple_links);
        mModerationHoldPref = getClickPref(R.string.pref_key_site_moderation_hold);
        mBlacklistPref = getClickPref(R.string.pref_key_site_blacklist);
        mImageWidthPref = (DetailListPreference) getChangePref(R.string.pref_key_site_image_width);
        mUploadAndLinkPref = (WPSwitchPreference) getChangePref(R.string.pref_key_site_upload_and_link_image);
        mStartOverPref = getClickPref(R.string.pref_key_site_start_over);
        mExportSitePref = getClickPref(R.string.pref_key_site_export_site);
        mDeleteSitePref = getClickPref(R.string.pref_key_site_delete_site);

        // .com sites hide the Account category, self-hosted sites hide the Related Posts preference
        if (mBlog.isDotcomFlag()) {
            removeSelfHostedOnlyPreferences();
        } else {
            removeDotComOnlyPreferences();
        }

        // hide all options except for Delete site and Enable Location if user is not admin
        if (!mBlog.isAdmin()) hideAdminRequiredPreferences();
    }

    private void showRelatedPostsDialog() {
        DialogFragment relatedPosts = new RelatedPostsDialog();
        Bundle args = new Bundle();
        args.putBoolean(RelatedPostsDialog.SHOW_RELATED_POSTS_KEY, mSiteSettings.getShowRelatedPosts());
        args.putBoolean(RelatedPostsDialog.SHOW_HEADER_KEY, mSiteSettings.getShowRelatedPostHeader());
        args.putBoolean(RelatedPostsDialog.SHOW_IMAGES_KEY, mSiteSettings.getShowRelatedPostImages());
        relatedPosts.setArguments(args);
        relatedPosts.setTargetFragment(this, RELATED_POSTS_REQUEST_CODE);
        relatedPosts.show(getFragmentManager(), "related-posts");
    }

    private void showNumberPickerDialog(Bundle args, int requestCode, String tag) {
        NumberPickerDialog dialog = new NumberPickerDialog();
        dialog.setArguments(args);
        dialog.setTargetFragment(this, requestCode);
        dialog.show(getFragmentManager(), tag);
    }

    private void showPagingDialog() {
        Bundle args = new Bundle();
        args.putBoolean(NumberPickerDialog.SHOW_SWITCH_KEY, true);
        args.putBoolean(NumberPickerDialog.SWITCH_ENABLED_KEY, mSiteSettings.getShouldPageComments());
        args.putString(NumberPickerDialog.SWITCH_TITLE_KEY, getString(R.string.site_settings_paging_title));
        args.putString(NumberPickerDialog.TITLE_KEY, getString(R.string.site_settings_paging_title));
        args.putString(NumberPickerDialog.HEADER_TEXT_KEY, getString(R.string.site_settings_paging_dialog_header));
        args.putInt(NumberPickerDialog.MIN_VALUE_KEY, 1);
        args.putInt(NumberPickerDialog.MAX_VALUE_KEY, getResources().getInteger(R.integer.paging_limit));
        args.putInt(NumberPickerDialog.CUR_VALUE_KEY, mSiteSettings.getPagingCount());
        showNumberPickerDialog(args, PAGING_REQUEST_CODE, "paging-dialog");
    }

    private void showExportContentDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        builder.setTitle("Export content");
        builder.setMessage("Currently exporting is only available through the web interface. Please go to WordPress.com on your browser to export content.");
        builder.setNeutralButton("OK", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
            }
        });

        builder.show();
    }

    private void showDeleteSiteDialog() {
        DeleteSiteDialogFragment deleteSiteDialogFragment = new DeleteSiteDialogFragment();
        deleteSiteDialogFragment.setTargetFragment(this, DELETE_SITE_REQUEST_CODE);
        deleteSiteDialogFragment.show(getFragmentManager(), "delete-site");
    }

    private void showCloseAfterDialog() {
        Bundle args = new Bundle();
        args.putBoolean(NumberPickerDialog.SHOW_SWITCH_KEY, true);
        args.putBoolean(NumberPickerDialog.SWITCH_ENABLED_KEY, mSiteSettings.getShouldCloseAfter());
        args.putString(NumberPickerDialog.SWITCH_TITLE_KEY, getString(R.string.site_settings_close_after_dialog_switch_text));
        args.putString(NumberPickerDialog.TITLE_KEY, getString(R.string.site_settings_close_after_dialog_title));
        args.putString(NumberPickerDialog.HEADER_TEXT_KEY, getString(R.string.site_settings_close_after_dialog_header));
        args.putInt(NumberPickerDialog.MIN_VALUE_KEY, 1);
        args.putInt(NumberPickerDialog.MAX_VALUE_KEY, getResources().getInteger(R.integer.close_after_limit));
        args.putInt(NumberPickerDialog.CUR_VALUE_KEY, mSiteSettings.getCloseAfter());
        showNumberPickerDialog(args, CLOSE_AFTER_REQUEST_CODE, "close-after-dialog");
    }

    private void showMultipleLinksDialog() {
        Bundle args = new Bundle();
        args.putBoolean(NumberPickerDialog.SHOW_SWITCH_KEY, false);
        args.putString(NumberPickerDialog.TITLE_KEY, getString(R.string.site_settings_multiple_links_title));
        args.putInt(NumberPickerDialog.MIN_VALUE_KEY, 0);
        args.putInt(NumberPickerDialog.MAX_VALUE_KEY, getResources().getInteger(R.integer.max_links_limit));
        args.putInt(NumberPickerDialog.CUR_VALUE_KEY, mSiteSettings.getMultipleLinks());
        showNumberPickerDialog(args, MULTIPLE_LINKS_REQUEST_CODE, "multiple-links-dialog");
    }

    private void setPreferencesFromSiteSettings() {
        mLocationPref.setChecked(mSiteSettings.getLocation());
        changeEditTextPreferenceValue(mTitlePref, mSiteSettings.getTitle());
        changeEditTextPreferenceValue(mTaglinePref, mSiteSettings.getTagline());
        changeEditTextPreferenceValue(mAddressPref, mSiteSettings.getAddress());
        changeEditTextPreferenceValue(mUsernamePref, mSiteSettings.getUsername());
        changeEditTextPreferenceValue(mPasswordPref, mSiteSettings.getPassword());
        changeLanguageValue(mSiteSettings.getLanguageCode());
        setDetailListPreferenceValue(mPrivacyPref,
                String.valueOf(mSiteSettings.getPrivacy()),
                mSiteSettings.getPrivacyDescription());
        setDetailListPreferenceValue(mImageWidthPref,
                mBlog.getMaxImageWidth(),
                mBlog.getMaxImageWidth());
        setCategories();
        setPostFormats();
        setAllowComments(mSiteSettings.getAllowComments());
        setSendPingbacks(mSiteSettings.getSendPingbacks());
        setReceivePingbacks(mSiteSettings.getReceivePingbacks());
        setDetailListPreferenceValue(mSortByPref,
                String.valueOf(mSiteSettings.getCommentSorting()),
                mSiteSettings.getSortingDescription());
        setDetailListPreferenceValue(mThreadingPref,
                String.valueOf(mSiteSettings.getThreadingLevels()),
                mSiteSettings.getThreadingDescription());
        int approval = mSiteSettings.getManualApproval() ?
                mSiteSettings.getUseCommentWhitelist() ? 0
                        : -1 : 1;
        setDetailListPreferenceValue(mWhitelistPref, String.valueOf(approval), getWhitelistSummary(approval));
        mMultipleLinksPref.setSummary(getResources()
                .getQuantityString(R.plurals.site_settings_multiple_links_summary,
                        mSiteSettings.getMultipleLinks(),
                        mSiteSettings.getMultipleLinks()));
        mUploadAndLinkPref.setChecked(mBlog.isFullSizeImage());
        mIdentityRequiredPreference.setChecked(mSiteSettings.getIdentityRequired());
        mUserAccountRequiredPref.setChecked(mSiteSettings.getUserAccountRequired());
        mThreadingPref.setValue(String.valueOf(mSiteSettings.getThreadingLevels()));
        mCloseAfterPref.setSummary(mSiteSettings.getCloseAfterDescription());
        mPagingPref.setSummary(mSiteSettings.getPagingDescription());
    }

    private void setCategories() {
        // Ignore if there are no changes
        if (mSiteSettings.isSameCategoryList(mCategoryPref.getEntryValues())) {
            mCategoryPref.setValue(String.valueOf(mSiteSettings.getDefaultCategory()));
            mCategoryPref.setSummary(mSiteSettings.getDefaultCategoryForDisplay());
            return;
        }

        Map<Integer, String> categories = mSiteSettings.getCategoryNames();
        CharSequence[] entries = new CharSequence[categories.size()];
        CharSequence[] values = new CharSequence[categories.size()];
        int i = 0;
        for (Integer key : categories.keySet()) {
            entries[i] = categories.get(key);
            values[i] = String.valueOf(key);
            if (key == UNCATEGORIZED_CATEGORY_ID) {
                CharSequence temp = entries[0];
                entries[0] = entries[i];
                entries[i] = temp;
                temp = values[0];
                values[0] = values[i];
                values[i] = temp;
            }
            ++i;
        }

        mCategoryPref.setEntries(entries);
        mCategoryPref.setEntryValues(values);
        mCategoryPref.setValue(String.valueOf(mSiteSettings.getDefaultCategory()));
        mCategoryPref.setSummary(mSiteSettings.getDefaultCategoryForDisplay());
    }

    private void setPostFormats() {
        // Ignore if there are no changes
        if (mSiteSettings.isSameFormatList(mFormatPref.getEntryValues())) {
            mFormatPref.setValue(String.valueOf(mSiteSettings.getDefaultPostFormat()));
            mFormatPref.setSummary(mSiteSettings.getDefaultPostFormatDisplay());
            return;
        }

        Map<String, String> formats = mSiteSettings.getFormats();
        String[] formatKeys = mSiteSettings.getFormatKeys();
        String[] entries = new String[formatKeys.length];
        String[] values = new String[formatKeys.length];

        for (int i = 0; i < entries.length; ++i) {
            entries[i] = formats.get(formatKeys[i]);
            values[i] = formatKeys[i];
        }

        mFormatPref.setEntries(entries);
        mFormatPref.setEntryValues(values);
        mFormatPref.setValue(String.valueOf(mSiteSettings.getDefaultPostFormat()));
        mFormatPref.setSummary(mSiteSettings.getDefaultPostFormatDisplay());
    }

    private void setAllowComments(boolean newValue) {
        mSiteSettings.setAllowComments(newValue);
        mAllowCommentsPref.setChecked(newValue);
        mAllowCommentsNested.setChecked(newValue);
    }

    private void setSendPingbacks(boolean newValue) {
        mSiteSettings.setSendPingbacks(newValue);
        mSendPingbacksPref.setChecked(newValue);
        mSendPingbacksNested.setChecked(newValue);
    }

    private void setReceivePingbacks(boolean newValue) {
        mSiteSettings.setReceivePingbacks(newValue);
        mReceivePingbacksPref.setChecked(newValue);
        mReceivePingbacksNested.setChecked(newValue);
    }

    private void setDetailListPreferenceValue(DetailListPreference pref, String value, String summary) {
        pref.setValue(value);
        pref.setSummary(summary);
        pref.refreshAdapter();
    }

    /**
     * Helper method to perform validation and set multiple properties on an EditTextPreference.
     * If newValue is equal to the current preference text no action will be taken.
     */
    private void changeEditTextPreferenceValue(EditTextPreference pref, String newValue) {
        if (newValue == null || pref == null || pref.getEditText().isInEditMode()) return;

        if (!newValue.equals(pref.getSummary())) {
            String formattedValue = StringUtils.unescapeHTML(newValue.replaceFirst(ADDRESS_FORMAT_REGEX, ""));

            pref.setText(formattedValue);
            pref.setSummary(formattedValue);
        }
    }

    /**
     * Detail strings for the dialog are generated in the selected language.
     *
     * @param newValue
     * languageCode
     */
    private void changeLanguageValue(String newValue) {
        if (mLanguagePref == null || newValue == null) return;

        if (TextUtils.isEmpty(mLanguagePref.getSummary()) ||
                !newValue.equals(mLanguagePref.getValue())) {
            mLanguagePref.setValue(newValue);
            String summary = getLanguageString(newValue, WPPrefUtils.languageLocale(newValue));
            mLanguagePref.setSummary(summary);

            // update details to display in selected locale
            CharSequence[] languageCodes = mLanguagePref.getEntryValues();
            mLanguagePref.setEntries(createLanguageDisplayStrings(languageCodes));
            mLanguagePref.setDetails(createLanguageDetailDisplayStrings(languageCodes, newValue));
            mLanguagePref.refreshAdapter();
        }
    }

    private String getWhitelistSummary(int value) {
        if (isAdded()) {
            switch (value) {
                case -1:
                    return getString(R.string.site_settings_whitelist_none_summary);
                case 0:
                    return getString(R.string.site_settings_whitelist_known_summary);
                case 1:
                    return getString(R.string.site_settings_whitelist_all_summary);
            }
        }
        return "";
    }

    private void updateWhitelistSettings(int val) {
        switch (val) {
            case -1:
                mSiteSettings.setManualApproval(true);
                mSiteSettings.setUseCommentWhitelist(false);
                break;
            case 0:
                mSiteSettings.setManualApproval(true);
                mSiteSettings.setUseCommentWhitelist(true);
                break;
            case 1:
                mSiteSettings.setManualApproval(false);
                mSiteSettings.setUseCommentWhitelist(false);
                break;
        }
        setDetailListPreferenceValue(mWhitelistPref,
                String.valueOf(val),
                getWhitelistSummary(val));
    }

    private void showStartOverDialog() {
        Dialog dialog = new Dialog(getActivity(), R.style.Calypso_SiteSettingsTheme);
        Context themer = new ContextThemeWrapper(getActivity(), R.style.Calypso_SiteSettingsTheme);
        View view = View.inflate(themer, R.layout.start_over_view, null);
        Button contactSupportButton = (Button) view.findViewById(R.id.contact_support_button);
        contactSupportButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                HelpshiftHelper.getInstance().showConversation(getActivity(), HelpshiftHelper.Tag.ORIGIN_START_OVER);
            }
        });
        dialog.setContentView(view);
        dialog.show();
        WPActivityUtils.addToolbarToDialog(this, dialog, getString(R.string.start_over));
    }

    private void showListEditorDialog(int titleRes, int footerRes) {
        Dialog dialog = new Dialog(getActivity(), R.style.Calypso_SiteSettingsTheme);
        dialog.setOnDismissListener(this);
        dialog.setContentView(getListEditorView(dialog, getString(footerRes)));
        dialog.show();
        WPActivityUtils.addToolbarToDialog(this, dialog, getString(titleRes));
    }

    private View getListEditorView(final Dialog dialog, String footerText) {
        Context themer = new ContextThemeWrapper(getActivity(), R.style.Calypso_SiteSettingsTheme);
        View view = View.inflate(themer, R.layout.list_editor, null);
        ((TextView) view.findViewById(R.id.list_editor_footer_text)).setText(footerText);

        final MultiSelectListView list = (MultiSelectListView) view.findViewById(android.R.id.list);
        list.setEnterMultiSelectListener(new MultiSelectListView.OnEnterMultiSelect() {
            @Override
            public void onEnterMultiSelect() {
                WPActivityUtils.setStatusBarColor(dialog.getWindow(), R.color.action_mode_status_bar_tint);
            }
        });
        list.setExitMultiSelectListener(new MultiSelectListView.OnExitMultiSelect() {
            @Override
            public void onExitMultiSelect() {
                WPActivityUtils.setStatusBarColor(dialog.getWindow(), R.color.status_bar_tint);
            }
        });
        list.setDeleteRequestListener(new MultiSelectListView.OnDeleteRequested() {
            @Override
            public boolean onDeleteRequested() {
                SparseBooleanArray checkedItems = list.getCheckedItemPositions();

                HashMap<String, Object> properties = new HashMap<>();
                properties.put("num_items_deleted", checkedItems.size());
                AnalyticsUtils.trackWithCurrentBlogDetails(
                        AnalyticsTracker.Stat.SITE_SETTINGS_DELETED_LIST_ITEMS, properties);

                ListAdapter adapter = list.getAdapter();
                List<String> itemsToRemove = new ArrayList<>();
                for (int i = 0; i < checkedItems.size(); i++) {
                    final int index = checkedItems.keyAt(i);
                    if (checkedItems.get(index)) {
                        itemsToRemove.add(adapter.getItem(index).toString());
                    }
                }
                mEditingList.removeAll(itemsToRemove);
                list.setAdapter(new ArrayAdapter<>(getActivity(),
                        R.layout.wp_simple_list_item_1,
                        mEditingList));
                mSiteSettings.saveSettings();
                return true;
            }
        });
        list.setEmptyView(view.findViewById(R.id.empty_view));
        list.setChoiceMode(AbsListView.CHOICE_MODE_MULTIPLE);
        list.setAdapter(new ArrayAdapter<>(getActivity(),
                R.layout.wp_simple_list_item_1,
                mEditingList));
        view.findViewById(R.id.fab_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AlertDialog.Builder builder =
                        new AlertDialog.Builder(getActivity(), R.style.Calypso_AlertDialog);
                final EditText input = new EditText(getActivity());
                WPPrefUtils.layoutAsInput(input);
                input.setHint(R.string.site_settings_list_editor_input_hint);
                builder.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String entry = input.getText().toString();
                        if (!mEditingList.contains(entry)) {
                            mEditingList.add(entry);
                            list.setAdapter(new ArrayAdapter<>(getActivity(),
                                    R.layout.wp_simple_list_item_1,
                                    mEditingList));
                            mSiteSettings.saveSettings();
                            AnalyticsUtils.trackWithCurrentBlogDetails(
                                    AnalyticsTracker.Stat.SITE_SETTINGS_ADDED_LIST_ITEM);
                        }
                    }
                });
                builder.setNegativeButton(R.string.cancel, null);
                AlertDialog alertDialog = builder.create();
                int spacing = getResources().getDimensionPixelSize(R.dimen.dlp_padding_start);
                alertDialog.setView(input, spacing, spacing, spacing, 0);
                alertDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
                alertDialog.show();
                alertDialog.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
                Button positive = alertDialog.getButton(DialogInterface.BUTTON_POSITIVE);
                Button negative = alertDialog.getButton(DialogInterface.BUTTON_NEGATIVE);
                if (positive != null) WPPrefUtils.layoutAsFlatButton(positive);
                if (negative != null) WPPrefUtils.layoutAsFlatButton(negative);
                WPActivityUtils.showKeyboard(input);
            }
        });

        return view;
    }

    private void removeBlog() {
        if (WordPress.wpDB.deleteBlog(getActivity(), mBlog.getLocalTableBlogId())) {
            StatsTable.deleteStatsForBlog(getActivity(), mBlog.getLocalTableBlogId()); // Remove stats data
            AnalyticsUtils.refreshMetadata();
            ToastUtils.showToast(getActivity(), R.string.blog_removed_successfully);
            WordPress.wpDB.deleteLastBlogId();
            WordPress.currentBlog = null;
            getActivity().setResult(RESULT_BLOG_REMOVED);

            // If the last blog is removed and the user is not signed in wpcom, broadcast a UserSignedOut event
            if (!AccountHelper.isSignedIn()) {
                EventBus.getDefault().post(new CoreEvents.UserSignedOutCompletely());
            }

            // Checks for stats widgets that were synched with a blog that could be gone now.
            StatsWidgetProvider.updateWidgetsOnLogout(getActivity());

            getActivity().finish();
        } else {
            AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(getActivity());
            dialogBuilder.setTitle(getResources().getText(R.string.error));
            dialogBuilder.setMessage(getResources().getText(R.string.could_not_remove_account));
            dialogBuilder.setPositiveButton(R.string.ok, null);
            dialogBuilder.setCancelable(true);
            dialogBuilder.create().show();
        }
    }

    private void removeBlogWithConfirmation() {
        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(getActivity());
        dialogBuilder.setTitle(getResources().getText(R.string.remove_account));
        dialogBuilder.setMessage(getResources().getText(R.string.sure_to_remove_account));
        dialogBuilder.setPositiveButton(getResources().getText(R.string.yes), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int whichButton) {
                removeBlog();
            }
        });
        dialogBuilder.setNegativeButton(getResources().getText(R.string.no), null);
        dialogBuilder.setCancelable(false);
        dialogBuilder.create().show();
    }

    private boolean shouldShowListPreference(DetailListPreference preference) {
        return preference != null && preference.getEntries() != null && preference.getEntries().length > 0;
    }

    /**
     * Generates display strings for given language codes. Used as entries in language preference.
     */
    private String[] createLanguageDisplayStrings(CharSequence[] languageCodes) {
        if (languageCodes == null || languageCodes.length < 1) return null;

        String[] displayStrings = new String[languageCodes.length];

        for (int i = 0; i < languageCodes.length; ++i) {
            displayStrings[i] = StringUtils.capitalize(getLanguageString(
                    String.valueOf(languageCodes[i]), WPPrefUtils.languageLocale(languageCodes[i].toString())));
        }

        return displayStrings;
    }

    /**
     * Generates detail display strings in the currently selected locale. Used as detail text
     * in language preference dialog.
     */
    public String[] createLanguageDetailDisplayStrings(CharSequence[] languageCodes, String locale) {
        if (languageCodes == null || languageCodes.length < 1) return null;

        String[] detailStrings = new String[languageCodes.length];
        for (int i = 0; i < languageCodes.length; ++i) {
            detailStrings[i] = StringUtils.capitalize(
                    getLanguageString(languageCodes[i].toString(), WPPrefUtils.languageLocale(locale)));
        }

        return detailStrings;
    }

    /**
     * Return a non-null display string for a given language code.
     */
    private String getLanguageString(String languageCode, Locale displayLocale) {
        if (languageCode == null || languageCode.length() < 2 || languageCode.length() > 6) {
            return "";
        }

        Locale languageLocale = WPPrefUtils.languageLocale(languageCode);
        String displayLanguage = StringUtils.capitalize(languageLocale.getDisplayLanguage(displayLocale));
        String displayCountry = languageLocale.getDisplayCountry(displayLocale);

        if (!TextUtils.isEmpty(displayCountry)) {
            return displayLanguage + " (" + displayCountry + ")";
        }
        return displayLanguage;
    }

    private void hideAdminRequiredPreferences() {
        WPPrefUtils.removePreference(this, R.string.pref_key_site_screen, R.string.pref_key_site_general);
        WPPrefUtils.removePreference(this, R.string.pref_key_site_screen, R.string.pref_key_site_account);
        WPPrefUtils.removePreference(this, R.string.pref_key_site_screen, R.string.pref_key_site_discussion);
        WPPrefUtils.removePreference(this, R.string.pref_key_site_writing, R.string.pref_key_site_category);
        WPPrefUtils.removePreference(this, R.string.pref_key_site_writing, R.string.pref_key_site_format);
        WPPrefUtils.removePreference(this, R.string.pref_key_site_writing, R.string.pref_key_site_related_posts);
    }

    private void removeDotComOnlyPreferences() {
        WPPrefUtils.removePreference(this, R.string.pref_key_site_general, R.string.pref_key_site_language);
        WPPrefUtils.removePreference(this, R.string.pref_key_site_writing, R.string.pref_key_site_related_posts);
    }

    private void removeSelfHostedOnlyPreferences() {
        WPPrefUtils.removePreference(this, R.string.pref_key_site_screen, R.string.pref_key_site_account);
        WPPrefUtils.removePreference(this, R.string.pref_key_site_screen, R.string.pref_key_site_delete_site_screen);
    }

    private Preference getChangePref(int id) {
        return WPPrefUtils.getPrefAndSetChangeListener(this, id, this);
    }

    private Preference getClickPref(int id) {
        return WPPrefUtils.getPrefAndSetClickListener(this, id, this);
    }

    private void deleteSite() {
        final Blog currentBlog = WordPress.getCurrentBlog();
        WordPress.getRestClientUtils().deleteSite(currentBlog.getDotComBlogId(), new RestRequest.Listener() {
                    @Override
                    public void onResponse(JSONObject response) {
                        removeBlog();
                    }
                }, new RestRequest.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                    }
                });
    }
}
