package io.stormbird.wallet.ui;

import android.arch.lifecycle.ViewModelProviders;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import javax.inject.Inject;

import dagger.android.AndroidInjection;
import io.stormbird.wallet.C;
import io.stormbird.wallet.R;
import io.stormbird.wallet.entity.*;
import io.stormbird.wallet.router.EthereumInfoRouter;
import io.stormbird.wallet.router.SendTokenRouter;
import io.stormbird.wallet.ui.widget.adapter.TokensAdapter;
import io.stormbird.wallet.ui.widget.adapter.TransactionsAdapter;
import io.stormbird.wallet.viewmodel.Erc20DetailViewModel;
import io.stormbird.wallet.viewmodel.Erc20DetailViewModelFactory;

import static io.stormbird.wallet.C.Key.WALLET;

public class Erc20DetailActivity extends BaseActivity {
    @Inject
    Erc20DetailViewModelFactory erc20DetailViewModelFactory;
    Erc20DetailViewModel viewModel;

    private static final int HISTORY_LENGTH = 3;

    private boolean sendingTokens = false;
    private boolean hasDefinition = false;
    private String myAddress;
    private int decimals;
    private String symbol;
    private Wallet wallet;
    private Token token;
    private String contractAddress;

    private Button sendBtn;
    private Button receiveBtn;
    private ProgressBar progressBar;
    private LinearLayout noTransactionsLayout;
    private TextView noTransactionsSubText;
    private RecyclerView tokenView;
    private RecyclerView recentTransactionsView;

    private TransactionsAdapter recentTransactionsAdapter;
    private TokensAdapter tokenViewAdapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        AndroidInjection.inject(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_erc20_token_detail);
        toolbar();
        setTitle("");
        getIntentData();
        myAddress = wallet.address;

        viewModel = ViewModelProviders.of(this, erc20DetailViewModelFactory)
                .get(Erc20DetailViewModel.class);
        viewModel.defaultWallet().observe(this, this::onDefaultWallet);
        viewModel.transactions().observe(this, this::onTransactions);
        viewModel.token().observe(this, this::onTokenData);
        viewModel.tokenTicker().observe(this, this::onTokenTicker);
        viewModel.transactionUpdate().observe(this, this::newTransactions);

        initViews();
    }

    private void onTokenTicker(Ticker ticker)
    {
        if (token != null)
        {
            token.ticker = new TokenTicker(String.valueOf(token.tokenInfo.chainId), token.getAddress(), ticker.price_usd, ticker.percentChange24h, null);
            Token[] tokens = {token};
            tokenViewAdapter.setTokens(tokens);
            tokenViewAdapter.notifyDataSetChanged();
        }
    }

    private void newTransactions(Transaction[] transactions)
    {
        recentTransactionsAdapter.addTransactions(transactions);
    }

    private void setUpRecentTransactionsView() {
        recentTransactionsView = findViewById(R.id.list);
        recentTransactionsView.setLayoutManager(new LinearLayoutManager(this));
        recentTransactionsAdapter = new TransactionsAdapter(this::onTransactionClick, viewModel.getTokensService(),
                viewModel.getTransactionsInteract(), R.layout.item_recent_transaction);
        recentTransactionsView.setAdapter(recentTransactionsAdapter);
    }

    private void setUpTokenView() {
        tokenView = findViewById(R.id.token_view);
        tokenView.setLayoutManager(new LinearLayoutManager(this) {
            @Override
            public boolean canScrollVertically() {
                return false;
            }
        });
        tokenViewAdapter = new TokensAdapter(this, null, viewModel.getAssetDefinitionService());
        Token[] tokens = {token};
        tokenViewAdapter.setTokens(tokens);
        tokenView.setAdapter(tokenViewAdapter);

        if (viewModel.hasIFrame(token.getAddress())) {
            addTokenPage();
        }
    }

    private void getIntentData() {
        contractAddress = getIntent().getStringExtra(C.EXTRA_CONTRACT_ADDRESS);
        decimals = getIntent().getIntExtra(C.EXTRA_DECIMALS, C.ETHER_DECIMALS);
        symbol = getIntent().getStringExtra(C.EXTRA_SYMBOL);
        symbol = symbol == null ? C.ETH_SYMBOL : symbol;
        sendingTokens = getIntent().getBooleanExtra(C.EXTRA_SENDING_TOKENS, false);
        wallet = getIntent().getParcelableExtra(WALLET);
        token = getIntent().getParcelableExtra(C.EXTRA_TOKEN_ID);
        hasDefinition = getIntent().getBooleanExtra(C.EXTRA_HAS_DEFINITION, false);
    }

    private void onTokenData(Token tokenUpdate)
    {
        tokenViewAdapter.clear();
        if (token.checkBalanceChange(tokenUpdate))
        {
            //trigger transaction fetch
            if (tokenUpdate.balanceIncrease(token)) playNotification();
            viewModel.listenNewTransactions(HISTORY_LENGTH);
            this.token = tokenUpdate; // update token
        }

        Token[] tokens = {token};
        tokenViewAdapter.setTokens(tokens);
        tokenViewAdapter.notifyDataSetChanged();
    }

    private void playNotification()
    {
        try
        {
            Uri notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            Ringtone r = RingtoneManager.getRingtone(getApplicationContext(), notification);
            r.play();
        }
        catch (Exception e)
        {
            //empty
        }
    }

    private void onTransactionClick(View view, Transaction transaction) {
        viewModel.showDetails(view.getContext(), transaction);
    }

    private void onTransactions(Transaction[] transactions) {
        progressBar.setVisibility(View.GONE);
        recentTransactionsView.setVisibility(View.VISIBLE);

        if (transactions.length > 0)
        {
            int txCount = recentTransactionsAdapter.updateRecentTransactions(transactions, contractAddress, myAddress, HISTORY_LENGTH);
            noTransactionsLayout.setVisibility(View.GONE);
            recentTransactionsAdapter.notifyDataSetChanged();
            if (txCount > 0)
            {
                viewModel.listenNewTransactions(0);
            }
        }
        else
        {
            noTransactionsLayout.setVisibility(View.VISIBLE);
        }
    }

    private void onNewEthPrice(Double ethPrice) {
        tokenViewAdapter.clear();
        token.setInterfaceSpec(ContractType.ETHEREUM);
        Token[] tokens = {token};
        tokenViewAdapter.setTokens(tokens);
        tokenViewAdapter.notifyDataSetChanged();
    }

    private void initViews() {
        noTransactionsLayout = findViewById(R.id.layout_no_recent_transactions);
        noTransactionsSubText = findViewById(R.id.no_recent_transactions_subtext);

        if (token.addressMatches(myAddress)) {
            noTransactionsSubText.setText(getString(R.string.no_recent_transactions_subtext,
                    getString(R.string.no_recent_transactions_subtext_ether)));
        } else {
            noTransactionsSubText.setText(getString(R.string.no_recent_transactions_subtext,
                    getString(R.string.no_recent_transactions_subtext_tokens)));
        }

        progressBar = findViewById(R.id.progress_bar);

        sendBtn = findViewById(R.id.button_send);
        sendBtn.setOnClickListener(v -> {
            if (sendingTokens)
            {
                viewModel.showSendToken(this, token);
            }
            else
            {
                viewModel.showSendToken(this, myAddress, token);
            }
        });

        receiveBtn = findViewById(R.id.button_receive);
        receiveBtn.setOnClickListener(v -> {
            viewModel.showMyAddress(this, wallet, token);
        });

        if (hasDefinition)
        {
            findViewById(R.id.text_confirmed).setVisibility(View.VISIBLE);
            findViewById(R.id.text_unconfirmed).setVisibility(View.GONE);
        }
        else
        {
            findViewById(R.id.layout_confirmed).setVisibility(View.GONE);
        }
    }

    private void addTokenPage() {
        LinearLayout viewWrapper = findViewById(R.id.layout_iframe);
        try {
            WebView iFrame = findViewById(R.id.iframe);
            String tokenData = viewModel.getTokenData(token.getAddress());
            iFrame.loadData(tokenData, "text/html", "UTF-8");
            viewWrapper.setVisibility(View.VISIBLE);
        } catch (Exception e) {
            viewWrapper.setVisibility(View.GONE);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home: {
                finish();
                break;
            }
            case R.id.action_qr:
                viewModel.showContractInfo(this, token);
                break;
        }
        return false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        viewModel.cleanUp();
    }

    private void onDefaultWallet(Wallet wallet) {
        setUpTokenView();
        setUpRecentTransactionsView();
        recentTransactionsAdapter.setDefaultWallet(wallet);
        viewModel.updateDefaultBalance(token);
        viewModel.fetchTransactions(token, HISTORY_LENGTH);
    }

    @Override
    public void onPause() {
        super.onPause();
        //stop updates
        viewModel.cleanUp();
    }

    @Override
    protected void onResume() {
        super.onResume();
        viewModel.prepare(token);
        viewModel.startEthereumTicker(token);
    }
}
