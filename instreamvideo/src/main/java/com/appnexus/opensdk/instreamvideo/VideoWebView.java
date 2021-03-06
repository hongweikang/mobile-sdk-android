/*
 *    Copyright 2016 APPNEXUS INC
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package com.appnexus.opensdk.instreamvideo;


import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.MutableContextWrapper;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.util.Pair;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.annotation.SuppressLint;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;


import com.appnexus.opensdk.AdActivity;
import com.appnexus.opensdk.BrowserAdActivity;
import com.appnexus.opensdk.ResultCode;
import com.appnexus.opensdk.ut.UTConstants;
import com.appnexus.opensdk.ut.adresponse.CSMVASTAdResponse;
import com.appnexus.opensdk.ut.adresponse.BaseAdResponse;
import com.appnexus.opensdk.utils.Clog;
import com.appnexus.opensdk.utils.Settings;
import com.appnexus.opensdk.utils.ViewUtil;
import com.appnexus.opensdk.utils.WebviewUtil;


class VideoWebView extends WebView {


    private VideoAd owner = null;
    private BaseAdResponse baseAdResponse;
    private ProgressDialog progressDialog;
    private boolean firstPageLoadComplete = false;
    private boolean adIsPlaying = false;
    private boolean failed = false;
    private VideoRequestManager manager;
    private static final int TOTAL_RETRY_TIMES = 10;
    private static final int WAIT_INTERVAL_MILLES = 300;
    private static final String WEBVIEW_URL = "file:///android_asset/index.html";

    // Using handler posts the playAd() call to the end of queue and fixes initial rendering black issue on Lollipop and below simulators.
    // And during resume Ad, this handler is used to retry until the parent window comes in focus else fail gracefully. Its observed parent window gets focus approx 200ms after the resume of activity.
    private Handler playAdHandler;

    public VideoWebView(Context context, VideoAd owner, VideoRequestManager manager) {
        super(new MutableContextWrapper(context));
        this.owner = owner;
        this.manager = manager;
        setupSettings();
        setup();
    }


    @SuppressWarnings("deprecation")
    @SuppressLint("SetJavaScriptEnabled")
    protected void setupSettings() {
        Settings.getSettings().ua = this.getSettings().getUserAgentString();
        this.getSettings().setJavaScriptEnabled(true);
        this.getSettings().setJavaScriptCanOpenWindowsAutomatically(true);
        this.getSettings().setBuiltInZoomControls(false);
        this.getSettings().setLightTouchEnabled(false);
        this.getSettings().setLoadsImagesAutomatically(true);
        this.getSettings().setSupportZoom(false);
        this.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
        this.getSettings().setUseWideViewPort(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            this.getSettings().setMediaPlaybackRequiresUserGesture(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            this.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        this.getSettings().setAllowFileAccess(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            this.getSettings().setAllowContentAccess(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            this.getSettings().setAllowFileAccessFromFileURLs(false);
            this.getSettings().setAllowUniversalAccessFromFileURLs(false);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager cm = CookieManager.getInstance();
            if (cm != null) {
                cm.setAcceptThirdPartyCookies(this, true);
            } else {
                Clog.d(Clog.videoLogTag, "Failed to set Webview to accept 3rd party cookie");
            }
        }

        setHorizontalScrollbarOverlay(false);
        setHorizontalScrollBarEnabled(false);
        setVerticalScrollbarOverlay(false);
        setVerticalScrollBarEnabled(false);


        setBackgroundColor(Color.TRANSPARENT);
        setScrollBarStyle(WebView.SCROLLBARS_INSIDE_OVERLAY);
    }

    @SuppressWarnings("deprecation")
    @SuppressLint("SetJavaScriptEnabled")
    protected void setup() {
        setWebChromeClient(new VideoChromeClient(owner));
        setWebViewClient(new AdWebViewClient());

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        setLayoutParams(params);
    }

    /**
     * AdWebViewClient for the webview
     */
    private class AdWebViewClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            Clog.v(Clog.baseLogTag, "Loading URL: " + url);
            if (url.startsWith("javascript:")) {
                return false;
            }
            if (url.startsWith("video://")) {
                dispatchNativeCallback(url);
                return true;
            }


            loadURLInCorrectBrowser(url);
            fireAdClicked();

            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            Clog.d(Clog.videoLogTag, "onPageFinished");
            if (!firstPageLoadComplete) {
                firstPageLoadComplete = true;
                if (baseAdResponse.getContentSource().equalsIgnoreCase(UTConstants.CSM_VIDEO)) {
                    processMediationAd();
                } else {
                    createVastPlayerWithContent();
                }
            }
        }


        @SuppressWarnings("deprecation")
        @Override
        public void onLoadResource(WebView view, String url) {

        }


        @Override
        public void onReceivedError(WebView view, int errorCode,
                                    String description, String failingURL) {
            Clog.d(Clog.videoLogTag, "error" + errorCode + description + failingURL);
        }
    }


    private void dispatchNativeCallback(String url) {
        url = url.replaceFirst("video://", "");
        if (url.equals("adReady")) {
            owner.getAdDispatcher().onAdLoaded();
        } else if (url.equals("video-error") || url.equals("Timed-out")) {
            handleVideoError();
        } else if (url.equals("video-skip")) {
            videoComplete();
            owner.getAdDispatcher().onVideoSkip();
        } else if (url.equals("video-first-quartile")) {
            owner.getAdDispatcher().onQuartile(Quartile.QUARTILE_FIRST);
        } else if (url.equals("video-mid")) {
            owner.getAdDispatcher().onQuartile(Quartile.QUARTILE_MID);
        } else if (url.equals("video-third-quartile")) {
            owner.getAdDispatcher().onQuartile(Quartile.QUARTILE_THIRD);
        } else if (url.equals("video-complete")) {
            videoComplete();
            owner.getAdDispatcher().onAdCompleted();
        } else if (url.equals("audio-mute")) {
            owner.getAdDispatcher().isAudioMute(true);
        } else if (url.equals("audio-unmute")) {
            owner.getAdDispatcher().isAudioMute(false);
        } else {
            Clog.e(Clog.videoLogTag, "Error: Unhandled event::" + url);
            return;
        }
    }

    private void handleVideoError() {
        if (adIsPlaying) {
            //Ad has failed during ad playback due to various reasons.
            owner.getAdDispatcher().onPlaybackError();
        } else {
            //Calling VideoRequestManager here and continue Waterfall. Or fire no_ad_url.
            //@TODO there is possiblity of capturing more granular failure responses here but for that HTML should be first setup to send back granular Error codes.Currently lets keep all as UNABLE_TO_FILL
            manager.continueWaterfall(ResultCode.UNABLE_TO_FILL);
        }
        if(!Settings.getSettings().debug_mode) {
            destroy();
        }
    }

    void fireAdClicked() {
        if (owner != null) {
            owner.getAdDispatcher().onAdClicked();
        }
    }

    void videoComplete() {
        adIsPlaying = false;
    }


    // handles browser logic for shouldOverrideUrl
    void loadURLInCorrectBrowser(String url) {
        if (!owner.getOpensNativeBrowser()) {

            Clog.d(Clog.baseLogTag, Clog.getString(R.string.opening_inapp));

            //If it's a direct URL to the play store, just open it.
            if (checkForApp(url)) {
                return;
            }

            try {

                final WebView out;
                // Unless disabled by the user, handle redirects in background

                if (owner.getLoadsInBackground()) {
                    // Otherwise, create an invisible 1x1 webview to load the landing
                    // page and detect if we're redirecting to a market url
                    out = new RedirectWebView(this.getContext());
                    out.loadUrl(url);
                    out.setVisibility(View.GONE);
                    // owner.addView(out);

                    if (this.owner.getShowLoadingIndicator()) {
                        //Show a dialog box
                        progressDialog = new ProgressDialog(this.getContextFromMutableContext());
                        progressDialog.setCancelable(true);
                        progressDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
                            @Override
                            public void onCancel(DialogInterface dialogInterface) {
                                out.stopLoading();
                            }
                        });
                        progressDialog.setMessage(getContext().getResources().getString(R.string.loading));
                        progressDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
                        progressDialog.show();
                    }
                } else {
                    // Stick the URL directly into the new activity.
                    out = new WebView(new MutableContextWrapper(getContext()));
                    WebviewUtil.setWebViewSettings(out);
                    out.loadUrl(url);
                    openInAppBrowser(out);
                }
            } catch (Exception e) {
                // Catches PackageManager$NameNotFoundException for webview
                Clog.e(Clog.baseLogTag, "Exception initializing the redirect webview: " + e.getMessage());
            }
        } else {
            Clog.d(Clog.baseLogTag,
                    Clog.getString(R.string.opening_native));
            openNativeIntent(url);
        }

    }

    // returns success or failure
    private boolean openNativeIntent(String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(intent);
            return true;
        } catch (ActivityNotFoundException e) {
            Clog.w(Clog.baseLogTag,
                    Clog.getString(R.string.opening_url_failed, url));
            return false;
        }
    }

    // returns success or failure
    private boolean checkForApp(String url) {
        if (url.contains("://play.google.com") || (!url.startsWith("http") && !url.startsWith("about:blank"))) {
            Clog.i(Clog.baseLogTag, Clog.getString(R.string.opening_app_store));
            return openNativeIntent(url);
        }

        return false;
    }

    private void openInAppBrowser(WebView fwdWebView) {
        Class<?> activity_clz = AdActivity.getActivityClass();

        Intent intent = new Intent(getContext(), activity_clz);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(AdActivity.INTENT_KEY_ACTIVITY_TYPE, AdActivity.ACTIVITY_TYPE_BROWSER);

        BrowserAdActivity.BROWSER_QUEUE.add(fwdWebView);
        if (owner.getBrowserStyle() != null) {
            String i = "" + super.hashCode();
            intent.putExtra("bridgeid", i);
            VideoAd.BrowserStyle.bridge
                    .add(new Pair<String, VideoAd.BrowserStyle>(i,
                            owner.getBrowserStyle()));
        }

        try {
            owner.getContext().startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Clog.w(Clog.baseLogTag, Clog.getString(R.string.adactivity_missing, activity_clz.getName()));
            BrowserAdActivity.BROWSER_QUEUE.remove();
        }
    }


    protected void injectJavaScript(String url) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            evaluateJavascript(url, null);
        } else {
            loadUrl(url);
        }
    }

    protected void playAd() {

        playAdHandler = new Handler();

        Runnable runnableCode = new Runnable() {
            int retryTimes= 0;
            @Override
            public void run() {
                // Play the Ad if WindowFocus is true else retry after 300ms
                if (hasWindowFocus()) {
                    adIsPlaying = true;
                    injectJavaScript("javascript:window.playAd()");
                }else if (retryTimes < TOTAL_RETRY_TIMES) {
                    Clog.i(Clog.videoLogTag,"Has no focus Retrying::"+retryTimes);
                    retryTimes ++;
                    playAdHandler.postDelayed(this, WAIT_INTERVAL_MILLES);
                }else{
                    Clog.e(Clog.videoLogTag,"Failed to play Video-Ad giving up");
                    owner.getAdDispatcher().onPlaybackError();
                }
            }
        };

        // There is no delay for first playAd() call
        playAdHandler.post(runnableCode);
    }

    protected void createVastPlayerWithContent() {
        String inject = String.format("javascript:window.createVastPlayerWithContent('%s')",
                baseAdResponse.getAdContent());
        this.injectJavaScript(inject);
    }

    private void processMediationAd() {
        String tag = ((CSMVASTAdResponse) baseAdResponse).getCSMVASTAdResponse();
        if (tag != null && !tag.isEmpty()) {
            String inject = String.format("javascript:window.processMediationAd('%s')",
                    tag);
            this.injectJavaScript(inject);
        }
    }


    protected void loadAd(BaseAdResponse baseAdResponse) {
        if (baseAdResponse == null) {
            fail();
            return;
        }
        this.baseAdResponse = baseAdResponse;
        this.loadUrl(WEBVIEW_URL);
    }


    private class RedirectWebView extends WebView {

        @SuppressLint("SetJavaScriptEnabled")
        public RedirectWebView(Context context) {
            super(new MutableContextWrapper(context));

            WebviewUtil.setWebViewSettings(this);
            this.setWebViewClient(new WebViewClient() {
                private boolean isOpeningAppStore = false;

                @Override
                public boolean shouldOverrideUrlLoading(WebView view, String url) {
                    Clog.v(Clog.browserLogTag, "Redirecting to URL: " + url);
                    isOpeningAppStore = checkForApp(url);

                    if (isOpeningAppStore) {
                        if (progressDialog != null && progressDialog.isShowing()) {
                            progressDialog.dismiss();
                        }
                    }

                    return isOpeningAppStore;
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    Clog.v(Clog.browserLogTag, "Opening URL: " + url);
                    ViewUtil.removeChildFromParent(RedirectWebView.this);

                    if (progressDialog != null && progressDialog.isShowing()) {
                        progressDialog.dismiss();
                    }

                    if (isOpeningAppStore) {
                        isOpeningAppStore = false;
                        RedirectWebView.this.destroy();
                        return;
                    }

                    RedirectWebView.this.setVisibility(View.VISIBLE);
                    openInAppBrowser(RedirectWebView.this);
                }
            });
        }
    }

    // Helper method for getting Context if it is of type MutableContextWrapper.
    protected Context getContextFromMutableContext() {
        if (this.getContext() instanceof MutableContextWrapper) {
            return ((MutableContextWrapper) this.getContext()).getBaseContext();
        }
        return this.getContext();
    }

    private void fail() {
        failed = true;
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    void resumeVideo() {
        // This is for resuming the playback after pause.
        if (adIsPlaying) {
            playAd();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        // The ad has been removed from window time to reset all the state variables.
        adIsPlaying = false;
        firstPageLoadComplete = false;
        failed = false;

    }

}

