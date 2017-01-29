package org.wordpress.android.ui.reader;

import android.app.Fragment;
import android.os.Bundle;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.widget.SearchView;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.wordpress.android.R;
import org.wordpress.android.models.ReaderBlog;
import org.wordpress.android.models.ReaderRecommendedBlog;
import org.wordpress.android.ui.reader.adapters.ReaderBlogAdapter;
import org.wordpress.android.ui.reader.adapters.ReaderBlogAdapter.ReaderBlogType;
import org.wordpress.android.ui.reader.views.ReaderRecyclerView;
import org.wordpress.android.util.AppLog;

/*
 * fragment hosted by ReaderSubsActivity which shows either recommended blogs and followed blogs
 */
public class ReaderBlogFragment extends Fragment
        implements ReaderBlogAdapter.BlogClickListener {
    private ReaderRecyclerView mRecyclerView;
    private ReaderBlogAdapter mAdapter;
    private ReaderBlogType mBlogType;
    private String mSearchConstraint;
    private boolean mWasPaused;

    private static final String ARG_BLOG_TYPE = "blog_type";
    private static final String KEY_SEARCH_CONSTRAINT = "search_constraint";

    static ReaderBlogFragment newInstance(ReaderBlogType blogType) {
        AppLog.d(AppLog.T.READER, "reader blog fragment > newInstance");
        Bundle args = new Bundle();
        args.putSerializable(ARG_BLOG_TYPE, blogType);
        ReaderBlogFragment fragment = new ReaderBlogFragment();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void setArguments(Bundle args) {
        super.setArguments(args);
        restoreState(args);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            AppLog.d(AppLog.T.READER, "reader blog fragment > restoring instance state");
            restoreState(savedInstanceState);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.reader_fragment_list, container, false);
        mRecyclerView = (ReaderRecyclerView) view.findViewById(R.id.recycler_view);

        // options menu (with search) only appears for followed blogs
        setHasOptionsMenu(getBlogType() == ReaderBlogType.FOLLOWED);

        return view;
    }

    private void checkEmptyView() {
        if (!isAdded()) return;

        TextView emptyView = (TextView) getView().findViewById(R.id.text_empty);
        if (emptyView == null) return;

        boolean isEmpty = hasBlogAdapter() && getBlogAdapter().isEmpty();
        if (isEmpty) {
            switch (getBlogType()) {
                case RECOMMENDED:
                    emptyView.setText(R.string.reader_empty_recommended_blogs);
                    break;
                case FOLLOWED:
                    if (getBlogAdapter().hasFilter()) {
                        emptyView.setText(R.string.reader_empty_followed_blogs_search_title);
                    } else {
                        emptyView.setText(R.string.reader_empty_followed_blogs_title);
                    }
                    break;
            }
        }
        emptyView.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        mRecyclerView.setAdapter(getBlogAdapter());
        refresh();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        outState.putSerializable(ARG_BLOG_TYPE, getBlogType());
        outState.putBoolean(ReaderConstants.KEY_WAS_PAUSED, mWasPaused);
        if (getBlogAdapter().hasFilter()) {
            outState.putString(KEY_SEARCH_CONSTRAINT, getBlogAdapter().getFilterConstraint());
        }
        super.onSaveInstanceState(outState);
    }

    private void restoreState(Bundle args) {
        if (args != null) {
            mWasPaused = args.getBoolean(ReaderConstants.KEY_WAS_PAUSED);
            if (args.containsKey(ARG_BLOG_TYPE)) {
                mBlogType = (ReaderBlogType) args.getSerializable(ARG_BLOG_TYPE);
            }
            if (args.containsKey(KEY_SEARCH_CONSTRAINT)) {
                mSearchConstraint = args.getString(KEY_SEARCH_CONSTRAINT);
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        mWasPaused = true;
    }

    @Override
    public void onResume() {
        super.onResume();
        // refresh the adapter if the fragment is resuming from a paused state so that changes
        // made in another activity (such as follow state) are reflected here
        if (mWasPaused) {
            mWasPaused = false;
            refresh();
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);

        inflater.inflate(R.menu.reader_subs, menu);

        MenuItem mnuSearch = menu.findItem(R.id.menu_search);
        final SearchView searchView = (SearchView) mnuSearch.getActionView();
        searchView.setQueryHint(getString(R.string.reader_hint_search_followed_sites));

        MenuItemCompat.setOnActionExpandListener(mnuSearch, new MenuItemCompat.OnActionExpandListener() {
            @Override
            public boolean onMenuItemActionExpand(MenuItem item) {
                return true;
            }
            @Override
            public boolean onMenuItemActionCollapse(MenuItem item) {
                setFilterConstraint(null);
                return true;
            }
        });

        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
               @Override
               public boolean onQueryTextSubmit(String query) {
                   setFilterConstraint(query);
                   return true;
               }
               @Override
               public boolean onQueryTextChange(String newText) {
                   setFilterConstraint(newText);
                   return true;
               }
           }
        );

        if (!TextUtils.isEmpty(mSearchConstraint)) {
            mnuSearch.expandActionView();
            searchView.clearFocus();
            searchView.post(new Runnable() {
                @Override
                public void run() {
                    if (isAdded()) {
                        searchView.setQuery(mSearchConstraint, true);
                    }
                }
            });
        }
    }

    void refresh() {
        if (hasBlogAdapter()) {
            AppLog.d(AppLog.T.READER, "reader subs > refreshing blog fragment " + getBlogType().name());
            getBlogAdapter().refresh();
        }
    }

    private void setFilterConstraint(String constraint) {
        mSearchConstraint = constraint;
        getBlogAdapter().setFilterConstraint(constraint);
    }

    private boolean hasBlogAdapter() {
        return (mAdapter != null);
    }

    private ReaderBlogAdapter getBlogAdapter() {
        if (mAdapter == null) {
            mAdapter = new ReaderBlogAdapter(getBlogType(), mSearchConstraint);
            mAdapter.setBlogClickListener(this);
            mAdapter.setDataLoadedListener(new ReaderInterfaces.DataLoadedListener() {
                @Override
                public void onDataLoaded(boolean isEmpty) {
                    checkEmptyView();
                }
            });

        }
        return mAdapter;
    }

    public ReaderBlogType getBlogType() {
        return mBlogType;
    }

    @Override
    public void onBlogClicked(Object item) {
        long blogId;
        long feedId;
        if (item instanceof ReaderRecommendedBlog) {
            ReaderRecommendedBlog blog = (ReaderRecommendedBlog) item;
            blogId = blog.blogId;
            feedId = 0;
        } else if (item instanceof ReaderBlog) {
            ReaderBlog blog = (ReaderBlog) item;
            blogId = blog.blogId;
            feedId = blog.feedId;
        } else {
            return;
        }

        if (feedId != 0) {
            ReaderActivityLauncher.showReaderFeedPreview(getActivity(), feedId);
        } else if (blogId != 0) {
            ReaderActivityLauncher.showReaderBlogPreview(getActivity(), blogId);
        }
    }
}
