package eu.intent.sdk.auth.internal;

import android.content.Context;
import android.content.SharedPreferences;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;

import java.io.IOException;
import java.util.concurrent.Semaphore;

import eu.intent.sdk.ITApp;
import eu.intent.sdk.api.internal.RetrofitGzipInterceptor;
import eu.intent.sdk.api.internal.RetrofitHeadersInterceptor;
import eu.intent.sdk.model.ITUser;
import eu.intent.sdk.util.ITConfig;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Call;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import retrofit2.http.Field;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.POST;

/**
 * A helper class to authenticate via OAuth.
 */
public final class Oauth {
    public static final String ACTION_SESSION_EXPIRED = "ACTION_SESSION_EXPIRED";

    private static final String CONFIG_BASE_URL = "it_auth_base_url";
    private static final String CONFIG_CLIENT_ID = "it_client_id";
    private static final String CONFIG_CLIENT_SECRET = "it_client_secret";
    private static final String CONFIG_REDIRECT_URL = "it_auth_redirect_url";
    private static final String GRANT_TYPE_CODE = "authorization_code";
    private static final String DEFAULT_BASE_URL = "https://accounts.hubintent.com";
    private static final String DEFAULT_REDIRECT_URL = "https://api.hubintent.com/reference/code";
    private static final String PREF_FILE_NAME = "IntentOauth.prefs";
    private static final String PREF_ACCESS_TOKEN = "access_token";
    private static final String PREF_ACCESS_TOKEN_EXPIRY = "access_token_expiry";
    private static final String PREF_REFRESH_TOKEN = "refresh_token";
    private static final String REFRESH_GRANT_TYPE = "refresh_token";

    private static Oauth sInstance;

    private SharedPreferences mPrefs;
    private ITConfig mConfig;
    private Service mService;

    // We need to call one refresh token request at a time, because a refresh token gets deprecated as soon as we generate a new one.
    private Semaphore mRefreshTokenSemaphore;

    private Oauth(Context context) {
        ITApp app = ITApp.getInstance(context);
        mPrefs = context.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE);
        mConfig = app == null ? new ITConfig(context, 0) : app.getConfig();
        OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder();
        clientBuilder.addInterceptor(new RetrofitGzipInterceptor());
        // TODO: Remove logs before releasing
        HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor();
        loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY);
        clientBuilder.addInterceptor(loggingInterceptor);
        clientBuilder.addNetworkInterceptor(new RetrofitHeadersInterceptor(context, this));
        Gson gson = new GsonBuilder().registerTypeAdapter(ITUser.class, new ITUser.Deserializer()).create();
        mService = new Retrofit.Builder().addConverterFactory(GsonConverterFactory.create(gson)).baseUrl(getBaseUrl()).client(clientBuilder.build()).build().create(Service.class);
        mRefreshTokenSemaphore = new Semaphore(1, true);
    }

    /**
     * @return an instance of Oauth.
     */
    public static Oauth getInstance(Context context) {
        synchronized (Oauth.class) {
            if (sInstance == null) {
                sInstance = new Oauth(context);
            }
        }
        return sInstance;
    }

    /**
     * Requests a new access token. The received token is saved locally to be reused when calling the API.
     */
    public void requestToken(final String code, final Callback callback) {
        Log.d(getClass().getCanonicalName(), "Request new access token (" + GRANT_TYPE_CODE + ")");
        mService.requestToken(GRANT_TYPE_CODE, code, getClientId(), getClientSecret()).enqueue(new retrofit2.Callback<Info>() {
            @Override
            public void onResponse(Call<Info> call, Response<Info> response) {
                saveAccessTokenFromResponse(response, callback);
            }

            @Override
            public void onFailure(Call<Info> call, Throwable t) {
                Log.w(Oauth.class.getCanonicalName(), t);
                if (callback != null) callback.onFailure(0, t.getLocalizedMessage());
            }
        });
    }

    /**
     * Requests a new access token from a refresh token. The received token is saved locally to be reused when calling the API.
     */
    public void refreshToken(@Nullable final Callback callback) {
        Log.d(getClass().getCanonicalName(), "Refresh access token");
        try {
            mRefreshTokenSemaphore.acquire();
            mService.refreshToken(REFRESH_GRANT_TYPE, getRefreshToken(), getClientId(), getClientSecret()).enqueue(new retrofit2.Callback<Info>() {
                @Override
                public void onResponse(Call<Info> call, Response<Info> response) {
                    saveAccessTokenFromResponse(response, callback);
                    mRefreshTokenSemaphore.release();
                }

                @Override
                public void onFailure(Call<Info> call, Throwable t) {
                    mRefreshTokenSemaphore.release();
                    Log.w(Oauth.class.getCanonicalName(), t);
                    if (callback != null) callback.onFailure(0, t.getLocalizedMessage());
                }
            });
        } catch (InterruptedException ignored) {
            // The thread has been interrupted while waiting for refreshing the token, abort.
        }
    }

    /**
     * Requests a new access token from a refresh token. The received token is saved locally to be reused when calling the API.
     * This method executes synchronously, you should be careful not to block the main thread when using it.
     *
     * @return the new access token
     */
    @Nullable
    public String refreshToken() throws IOException {
        Log.d(getClass().getCanonicalName(), "Refresh access token");
        String newAccessToken = null;
        try {
            mRefreshTokenSemaphore.acquire();
            Response<Info> response = mService.refreshToken(REFRESH_GRANT_TYPE, getRefreshToken(), getClientId(), getClientSecret()).execute();
            newAccessToken = saveAccessTokenFromResponse(response, null);
            mRefreshTokenSemaphore.release();
        } catch (InterruptedException ignored) {
            // The thread has been interrupted while waiting for refreshing the token, abort (this will return null).
        }
        return newAccessToken;
    }

    /**
     * Parses and saves the access token from a response body. If the response is a failure, the tokens are cleared.
     *
     * @param response the Response sent back by the access or refresh token request
     * @param callback a custom Callback if you need to forward the response body
     * @return the access token parsed from the response body, or null if the response is a failure
     */
    @Nullable
    private String saveAccessTokenFromResponse(@NonNull Response<Info> response, @Nullable Callback callback) {
        String accessToken = null;
        if (response.isSuccessful()) {
            Info body = response.body();
            accessToken = body.accessToken;
            saveToken(body.accessToken, body.refreshToken, body.expiresIn);
            if (callback != null) callback.onSuccess(body);
        } else {
            try {
                Log.e(Oauth.class.getCanonicalName(), response.errorBody().string());
            } catch (IOException ignored) {
                Log.e(Oauth.class.getCanonicalName(), response.message());
            }
            logout();
            if (callback != null)
                callback.onFailure(response.code(), response.message());
        }
        return accessToken;
    }

    /**
     * Logs out the current user. This basically clears all the saved tokens.
     */
    public void logout() {
        Log.d(Oauth.class.getName(), "Logout");
        saveToken("", "", 0);
    }

    /**
     * Returns true if there is a currently valid (not expired) access token stored from requestToken().
     */
    public boolean hasValidAccessToken() {
        String token = mPrefs.getString(PREF_ACCESS_TOKEN, "");
        long expiry = mPrefs.getLong(PREF_ACCESS_TOKEN_EXPIRY, 0);
        return !TextUtils.isEmpty(token) && expiry > System.currentTimeMillis();
    }

    /**
     * Returns the last access token, or an empty string if no access token was found. Please note that the returned token may have expired.
     */
    public String getAccessToken() {
        return mPrefs.getString(PREF_ACCESS_TOKEN, "");
    }

    /**
     * Returns the last refresh token, or an empty string if no refresh token was found. Please note that the returned token may have expired.
     */
    public String getRefreshToken() {
        return mPrefs.getString(PREF_REFRESH_TOKEN, "");
    }

    private void saveToken(String accessToken, String refreshToken, long expiresIn) {
        if (TextUtils.isEmpty(accessToken)) {
            Log.d(getClass().getCanonicalName(), "Cleared access token");
        } else {
            Log.d(getClass().getCanonicalName(), "Saved access token, expires in " + expiresIn + " seconds");
        }
        if (TextUtils.isEmpty(refreshToken)) {
            Log.d(getClass().getCanonicalName(), "Cleared refresh token");
        } else {
            Log.d(getClass().getCanonicalName(), "Saved refresh token");
        }
        long expiry = System.currentTimeMillis() + (expiresIn - 60) * 1000;   // Remove 1 minute to be sure
        mPrefs.edit()
                .putString(PREF_ACCESS_TOKEN, accessToken)
                .putString(PREF_REFRESH_TOKEN, refreshToken)
                .putLong(PREF_ACCESS_TOKEN_EXPIRY, expiry)
                .apply();
    }

    /**
     * Returns the default base URL. The base URL can be forced to a value stored in the configuration file.
     */
    public String getBaseUrl() {
        String baseUrl = mConfig.getString(CONFIG_BASE_URL);
        if (TextUtils.isEmpty(baseUrl)) {
            baseUrl = DEFAULT_BASE_URL;
        }
        return baseUrl;
    }

    /**
     * Returns the redirect URL for code grant type. The redirect URL can be forced to a value stored in the configuration file.
     */
    public String getRedirectUrl() {
        String redirectUrl = mConfig.getString(CONFIG_REDIRECT_URL);
        if (TextUtils.isEmpty(redirectUrl)) {
            redirectUrl = DEFAULT_REDIRECT_URL;
        }
        return redirectUrl;
    }

    /**
     * Retrieves the client ID from the configuration file.
     */
    public String getClientId() {
        return mConfig.getString(CONFIG_CLIENT_ID);
    }

    /**
     * Retrieves the client secret from the configuration file.
     */
    private String getClientSecret() {
        return mConfig.getString(CONFIG_CLIENT_SECRET);
    }

    /**
     * Implement these callback methods when requesting a token.
     */
    public interface Callback {

        /**
         * Called when a token is received.
         *
         * @param info the generated tokens and their expiry date
         */
        void onSuccess(Info info);

        /**
         * Called on error or authentication failure.
         */
        void onFailure(int httpCode, String message);
    }

    private interface Service {
        @FormUrlEncoded
        @POST("/oauth/token")
        Call<Info> requestToken(@Field("grant_type") String grantType, @Field("code") String code, @Field("client_id") String clientId, @Field("client_secret") String clientSecret);

        @FormUrlEncoded
        @POST("/oauth/token")
        Call<Info> refreshToken(@Field("grant_type") String grantType, @Field("refresh_token") String refreshToken, @Field("client_id") String clientId, @Field("client_secret") String clientSecret);
    }

    /**
     * The OAuth success body returned when a token has been requested.
     */
    public static class Info {
        @SerializedName("expires_in")
        public long expiresIn;
        @SerializedName("access_token")
        public String accessToken;
        @SerializedName("refresh_token")
        public String refreshToken;
        @SerializedName("token_type")
        public String tokenType;
    }
}
