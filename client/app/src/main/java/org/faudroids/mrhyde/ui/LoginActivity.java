package org.faudroids.mrhyde.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;

import org.eclipse.egit.github.core.User;
import org.eclipse.egit.github.core.service.UserService;
import org.faudroids.mrhyde.R;
import org.faudroids.mrhyde.app.MigrationManager;
import org.faudroids.mrhyde.github.GitHubAuthApi;
import org.faudroids.mrhyde.github.LoginManager;
import org.faudroids.mrhyde.github.TokenDetails;
import org.faudroids.mrhyde.ui.utils.AbstractActionBarActivity;
import org.faudroids.mrhyde.utils.DefaultErrorAction;
import org.faudroids.mrhyde.utils.DefaultTransformer;
import org.faudroids.mrhyde.utils.ErrorActionBuilder;
import org.faudroids.mrhyde.utils.HideSpinnerAction;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.net.ssl.HttpsURLConnection;

import roboguice.inject.ContentView;
import roboguice.inject.InjectView;
import rx.Observable;
import rx.functions.Action1;
import rx.functions.Func1;
import timber.log.Timber;


@ContentView(R.layout.activity_login)
public final class LoginActivity extends AbstractActionBarActivity {

	private static final String STATE_LOGIN_RUNNING = "STATE_LOGIN_RUNNING";
	private static final String GITHUB_LOGIN_STATE = UUID.randomUUID().toString();

	@InjectView(R.id.login_button) private Button loginButton;
	@Inject private GitHubAuthApi gitHubAuthApi;
	@Inject private LoginManager loginManager;
	@Inject private MigrationManager migrationManager;

	private Dialog loginDialog = null;
	private WebView loginView = null;
	private boolean loginRunning = false;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// start migration if necessary
		migrationManager.doMigration();

		// check if logged in
		if (loginManager.getAccount() != null) {
			onLoginSuccess();
			return;
		}

		// setup UI
		loginButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View arg0) {
				loginRunning = true;
				startLogin(null);
			}
		});

		// check for interrupted login attempt
		if (savedInstanceState != null) {
			loginRunning = savedInstanceState.getBoolean(STATE_LOGIN_RUNNING);
			if (loginRunning) {
				startLogin(savedInstanceState);
			}
		}
	}


	@Override
	public void onDestroy() {
		hideDialog();
		super.onDestroy();
	}


	@Override
	public void onSaveInstanceState(Bundle outState) {
		outState.putBoolean(STATE_LOGIN_RUNNING, loginRunning);
		if (loginView != null) loginView.saveState(outState);
		super.onSaveInstanceState(outState);
	}


	private void startLogin(Bundle savedState) {
		loginDialog = new Dialog(LoginActivity.this);
		loginDialog.setContentView(R.layout.dialog_login);
		loginView = (WebView) loginDialog.findViewById(R.id.webview);
		if (savedState != null) {
			loginView.restoreState(savedState);
		} else {
			loginView.loadUrl("https://github.com/login/oauth/authorize?"
					+ "&client_id=" + getString(R.string.gitHubClientId)
					+ "&scope=user%2Crepo"
					+ "&state=" + GITHUB_LOGIN_STATE);
		}
		loginView.setWebViewClient(new WebViewClient() {
			@Override
			public void onPageFinished(WebView view, String url) {
				super.onPageFinished(view, url);
				if (url.contains("code=")) {
					Uri uri = Uri.parse(url);
					String code = uri.getQueryParameter("code");
					if (!GITHUB_LOGIN_STATE.equals(uri.getQueryParameter("state"))) {
						Timber.w("GitHub login states did not match");
						onAccessDenied();
						return;
					}

					hideDialog();
					getAccessToken(code);

				} else if (url.contains("error=access_denied")) {
					hideDialog();
					onAccessDenied();
				}
			}
		});
		loginDialog.show();
		loginDialog.setTitle(getString(R.string.login_title));
		loginDialog.setCancelable(true);
	}


	private void getAccessToken(String code) {
		String clientId = getString(R.string.gitHubClientId);
		String clientSecret = getString(R.string.gitHubClientSecret);
		showSpinner();
		compositeSubscription.add(gitHubAuthApi.getAccessToken(clientId, clientSecret, code)
				.flatMap(new Func1<TokenDetails, Observable<LoginManager.Account>>() {
					@Override
					public Observable<LoginManager.Account> call(TokenDetails tokenDetails) {
						try {
							// load user
							UserService userService = new UserService();
							userService.getClient().setOAuth2Token(tokenDetails.getAccessToken());
							User user = userService.getUser();
							List<String> emails = userService.getEmails();

							// load avatar
							URL url = new URL(user.getAvatarUrl());
							HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
							connection.setDoInput(true);
							connection.connect();
							InputStream input = connection.getInputStream();
							Bitmap avatar = BitmapFactory.decodeStream(input);
							return Observable.just(new LoginManager.Account(tokenDetails.getAccessToken(), user.getLogin(), emails.get(0), avatar));

						} catch (IOException e) {
							return Observable.error(e);
						}
					}
				})
				.compose(new DefaultTransformer<LoginManager.Account>())
				.subscribe(new Action1<LoginManager.Account>() {
					@Override
					public void call(LoginManager.Account account) {
						Timber.d("gotten token " + account.getAccessToken());
						loginManager.setAccount(account);
						onLoginSuccess();
					}
				}, new ErrorActionBuilder()
						.add(new DefaultErrorAction(this, "failed to get token"))
						.add(new HideSpinnerAction(this))
						.build()));
	}


	private void onLoginSuccess() {
		startActivity(new Intent(LoginActivity.this, MainDrawerActivity.class));
		finish();
	}


	private void onAccessDenied() {
		new AlertDialog.Builder(this)
				.setTitle(R.string.login_error_title)
				.setMessage(R.string.login_error_message)
				.setPositiveButton(android.R.string.ok, null)
				.show();
	}


	private void hideDialog() {
		if (loginDialog == null) return;
		loginDialog.dismiss();
		loginDialog = null;
		loginView = null;
	}

}
