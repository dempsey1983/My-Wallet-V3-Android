package piuk.blockchain.android.ui.auth;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.StringRes;
import android.support.annotation.VisibleForTesting;
import android.util.Log;

import info.blockchain.wallet.api.data.Settings;
import info.blockchain.wallet.exceptions.DecryptionException;
import info.blockchain.wallet.exceptions.HDWalletException;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

import javax.inject.Inject;

import okhttp3.MediaType;
import okhttp3.ResponseBody;
import piuk.blockchain.android.R;
import piuk.blockchain.android.data.access.AccessState;
import piuk.blockchain.android.data.datamanagers.AuthDataManager;
import piuk.blockchain.android.injection.Injector;
import piuk.blockchain.android.ui.base.BaseViewModel;
import piuk.blockchain.android.ui.customviews.ToastCustom;
import piuk.blockchain.android.util.AppUtil;
import piuk.blockchain.android.util.DialogButtonCallback;
import piuk.blockchain.android.util.PrefsUtil;
import retrofit2.Response;

@SuppressWarnings("WeakerAccess")
public class PasswordRequiredViewModel extends BaseViewModel {

    @VisibleForTesting static final String KEY_AUTH_REQUIRED = "authorization_required";
    private static final String TAG = PasswordRequiredViewModel.class.getSimpleName();

    @Inject protected AppUtil appUtil;
    @Inject protected PrefsUtil prefsUtil;
    @Inject protected AuthDataManager authDataManager;
    @Inject protected AccessState accessState;
    private DataListener dataListener;
    private String sessionId;
    @VisibleForTesting boolean waitingForAuth = false;

    public interface DataListener {

        String getPassword();

        void resetPasswordField();

        void goToPinPage();

        void showToast(@StringRes int message, @ToastCustom.ToastType String toastType);

        void restartPage();

        void updateWaitingForAuthDialog(int secondsRemaining);

        void showProgressDialog(@StringRes int messageId, @Nullable String suffix, boolean cancellable);

        void dismissProgressDialog();

        void showForgetWalletWarning(DialogButtonCallback callback);

        void showTwoFactorCodeNeededDialog(JSONObject responseObject, String sessionId, int authType, String password);

    }

    PasswordRequiredViewModel(DataListener listener) {
        Injector.getInstance().getDataManagerComponent().inject(this);
        dataListener = listener;
    }

    @Override
    public void onViewReady() {
        // No-op
    }

    void onContinueClicked() {
        if (dataListener.getPassword().length() > 1) {
            verifyPassword(dataListener.getPassword());
        } else {
            dataListener.showToast(R.string.invalid_password, ToastCustom.TYPE_ERROR);
            dataListener.restartPage();
        }
    }

    void onForgetWalletClicked() {
        dataListener.showForgetWalletWarning(new DialogButtonCallback() {
            @Override
            public void onPositiveClicked() {
                appUtil.clearCredentialsAndRestart();
            }

            @Override
            public void onNegativeClicked() {
                // No-op
            }
        });
    }

    void submitTwoFactorCode(JSONObject responseObject, String sessionId, String password, String code) {
        if (code == null || code.isEmpty()) {
            dataListener.showToast(R.string.two_factor_null_error, ToastCustom.TYPE_ERROR);
        } else {
            String guid = prefsUtil.getValue(PrefsUtil.KEY_GUID, "");
            compositeDisposable.add(
                    authDataManager.submitTwoFactorCode(sessionId, guid, code)
                            .doOnSubscribe(disposable -> dataListener.showProgressDialog(R.string.please_wait, null, false))
                            .doAfterTerminate(() -> dataListener.dismissProgressDialog())
                            .subscribe(response -> {
                                        // This is slightly hacky, but if the user requires 2FA login,
                                        // the payload comes in two parts. Here we combine them and
                                        // parse/decrypt normally.
                                        responseObject.put("payload", response.string());
                                        ResponseBody responseBody = ResponseBody.create(
                                                MediaType.parse("application/json"),
                                                responseObject.toString());

                                        Response<ResponseBody> payload = Response.success(responseBody);
                                        handleResponse(password, guid, payload);
                                    },
                                    throwable -> showErrorToast(R.string.two_factor_incorrect_error)));
        }
    }

    private void verifyPassword(String password) {
        String guid = prefsUtil.getValue(PrefsUtil.KEY_GUID, "");
        waitingForAuth = true;

        compositeDisposable.add(
                authDataManager.getSessionId(guid)
                        .doOnSubscribe(disposable -> dataListener.showProgressDialog(R.string.validating_password, null, false))
                        .doOnNext(s -> sessionId = s)
                        .flatMap(sessionId -> authDataManager.getEncryptedPayload(guid, sessionId))
                        .subscribe(response -> handleResponse(password, guid, response),
                                throwable -> {
                                    Log.e(TAG, "verifyPassword: ", throwable);
                                    showErrorToastAndRestartApp(R.string.auth_failed);
                                }));
    }

    private void handleResponse(String password, String guid, Response<ResponseBody> response) throws IOException, JSONException {
        String errorBody = response.errorBody() != null ? response.errorBody().string() : "";
        if (errorBody.contains(KEY_AUTH_REQUIRED)) {
            showCheckEmailDialog();

            compositeDisposable.add(
                    authDataManager.startPollingAuthStatus(guid, sessionId)
                            .subscribe(payloadResponse -> {
                                waitingForAuth = false;

                                if (payloadResponse == null || payloadResponse.contains(KEY_AUTH_REQUIRED)) {
                                    showErrorToastAndRestartApp(R.string.auth_failed);
                                    return;
                                }

                                ResponseBody responseBody = ResponseBody.create(
                                        MediaType.parse("application/json"),
                                        payloadResponse);
                                checkTwoFactor(password, Response.success(responseBody));
                            }, throwable -> {
                                Log.e(TAG, "handleResponse: ", throwable);
                                waitingForAuth = false;
                                showErrorToastAndRestartApp(R.string.auth_failed);
                            }));
        } else {
            waitingForAuth = false;
            checkTwoFactor(password, response);
        }
    }

    private void checkTwoFactor(String password, Response<ResponseBody> response) throws
            IOException, JSONException {

        String responseBody = response.body().string();
        JSONObject jsonObject = new JSONObject(responseBody);
        // Check if the response has a 2FA Auth Type but is also missing the payload,
        // as it comes in two parts if 2FA enabled.
        if (jsonObject.has("auth_type") && !jsonObject.has("payload")
                && (jsonObject.getInt("auth_type") == Settings.AUTH_TYPE_GOOGLE_AUTHENTICATOR
                || jsonObject.getInt("auth_type") == Settings.AUTH_TYPE_SMS)) {

            dataListener.dismissProgressDialog();
            dataListener.showTwoFactorCodeNeededDialog(jsonObject,
                    sessionId,
                    jsonObject.getInt("auth_type"),
                    password);
        } else {
            attemptDecryptPayload(password, responseBody);
        }
    }

    private void attemptDecryptPayload(String password, String payload) {
        compositeDisposable.add(
                authDataManager.initializeFromPayload(payload, password)
                        .subscribe(() -> dataListener.goToPinPage(),
                                throwable -> {
                                    if (throwable instanceof HDWalletException) {
                                        showErrorToast(R.string.pairing_failed);
                                    } else if (throwable instanceof DecryptionException) {
                                        showErrorToast(R.string.auth_failed);
                                    } else {
                                        showErrorToastAndRestartApp(R.string.auth_failed);
                                    }
                                }));
    }

    private void showCheckEmailDialog() {
        dataListener.showProgressDialog(R.string.check_email_to_auth_login, "120", true);

        compositeDisposable.add(authDataManager.createCheckEmailTimer()
                .takeUntil(integer -> !waitingForAuth)
                .subscribe(integer -> {
                    if (integer <= 0) {
                        // Only called if timer has run out
                        showErrorToastAndRestartApp(R.string.pairing_failed);
                    } else {
                        dataListener.updateWaitingForAuthDialog(integer);
                    }
                }, throwable -> {
                    showErrorToast(R.string.auth_failed);
                    waitingForAuth = false;
                }));
    }

    void onProgressCancelled() {
        waitingForAuth = false;
        destroy();
    }

    private void showErrorToast(@StringRes int message) {
        dataListener.dismissProgressDialog();
        dataListener.resetPasswordField();
        dataListener.showToast(message, ToastCustom.TYPE_ERROR);
    }

    private void showErrorToastAndRestartApp(@StringRes int message) {
        dataListener.resetPasswordField();
        dataListener.dismissProgressDialog();
        dataListener.showToast(message, ToastCustom.TYPE_ERROR);
        appUtil.clearCredentialsAndRestart();
    }

    @NonNull
    AppUtil getAppUtil() {
        return appUtil;
    }
}
