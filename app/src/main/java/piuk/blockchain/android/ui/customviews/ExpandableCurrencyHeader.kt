package piuk.blockchain.android.ui.customviews

import android.annotation.TargetApi
import android.content.Context
import android.graphics.Outline
import android.os.Build
import android.support.annotation.DrawableRes
import android.support.annotation.StringRes
import android.support.v4.content.ContextCompat
import android.support.v7.content.res.AppCompatResources
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.Transformation
import android.widget.RelativeLayout
import kotlinx.android.synthetic.main.view_expanding_currency_header.view.*
import piuk.blockchain.android.R
import piuk.blockchain.android.data.currency.CryptoCurrencies
import piuk.blockchain.android.util.extensions.gone
import piuk.blockchain.android.util.extensions.invisible
import piuk.blockchain.android.util.extensions.setAnimationListener
import piuk.blockchain.android.util.extensions.visible


class ExpandableCurrencyHeader @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null
) : RelativeLayout(context, attrs) {

    private var expanded = false
    private var firstOpen = true

    private var collapsedHeight: Int = 0
    private var contentHeight: Int = 0
    private var contentWidth: Int = 0

    private lateinit var selectionListener: (CryptoCurrencies) -> Unit
    var selectedCurrency = CryptoCurrencies.BTC

    init {
        // Inflate layout
        LayoutInflater.from(getContext())
                .inflate(R.layout.view_expanding_currency_header, this, true)
        // Add compound drawables manually to avoid inflation errors on <21
        textview_bitcoin.setCompoundDrawablesWithIntrinsicBounds(
                AppCompatResources.getDrawable(context, R.drawable.vector_bitcoin),
                null,
                null,
                null
        )
        textview_ethereum.setCompoundDrawablesWithIntrinsicBounds(
                AppCompatResources.getDrawable(context, R.drawable.vector_eth),
                null,
                null,
                null
        )
        textview_bitcoin_cash.setCompoundDrawablesWithIntrinsicBounds(
                AppCompatResources.getDrawable(context, R.drawable.vector_bitcoin_cash),
                null,
                null,
                null
        )
        // Select Bitcoin by default but without triggering callback
        updateCurrencyUi(R.drawable.vector_bitcoin, R.string.bitcoin)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()

        linear_layout_coin_selection.invisible()

        textview_selected_currency.setOnClickListener { animateLayout(true) }

        textview_bitcoin.setOnClickListener { closeLayout(CryptoCurrencies.BTC) }
        textview_ethereum.setOnClickListener { closeLayout(CryptoCurrencies.ETHER) }
        textview_bitcoin_cash.setOnClickListener { closeLayout(CryptoCurrencies.BCH) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        content_frame.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        textview_selected_currency.measure(View.MeasureSpec.UNSPECIFIED, heightMeasureSpec)
        collapsedHeight = textview_selected_currency.measuredHeight
        contentWidth = content_frame.measuredWidth
        contentHeight = content_frame.measuredHeight

        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        if (firstOpen) {
            content_frame.layoutParams.width = contentWidth
            content_frame.layoutParams.height = collapsedHeight
            firstOpen = false
        }

        val width = textview_selected_currency.measuredWidth + content_frame.measuredWidth
        val height = content_frame.measuredHeight

        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            outlineProvider = CustomOutline(w, h)
        }
    }

    fun setSelectionListener(selectionListener: (CryptoCurrencies) -> Unit) {
        this.selectionListener = selectionListener
    }

    fun setCurrentlySelectedCurrency(cryptoCurrency: CryptoCurrencies) {
        // Prevent selecting the same thing twice needlessly
        if (selectedCurrency != cryptoCurrency) {
            selectedCurrency = cryptoCurrency
            when (selectedCurrency) {
                CryptoCurrencies.BTC ->
                    updateCurrencyUi(R.drawable.vector_bitcoin, R.string.bitcoin)
                CryptoCurrencies.ETHER ->
                    updateCurrencyUi(R.drawable.vector_eth, R.string.ethereum)
                CryptoCurrencies.BCH ->
                    updateCurrencyUi(R.drawable.vector_bitcoin_cash, R.string.bitcoin_cash)
            }
        }
    }

    fun hideEthereum() {
        textview_ethereum.gone()
    }

    private fun animateLayout(expanding: Boolean) {
        if (expanding) {
            textview_selected_currency.setOnClickListener(null)
            val animation = AlphaAnimation(1.0f, 0.0f).apply { duration = 250 }
            textview_selected_currency.startAnimation(animation)
            animation.setAnimationListener {
                onAnimationEnd {
                    textview_selected_currency.alpha = 0.0f
                    startContentAnimation()
                }
            }
        } else {
            textview_selected_currency.setOnClickListener { animateLayout(true) }
            startContentAnimation()
        }
    }

    private fun startContentAnimation() {
        val animation: Animation = if (expanded) {
            linear_layout_coin_selection.invisible()
            ExpandAnimation(contentHeight, collapsedHeight)
        } else {
            this@ExpandableCurrencyHeader.invalidate()
            ExpandAnimation(collapsedHeight, contentHeight)
        }

        animation.duration = 300L
        animation.setAnimationListener {
            onAnimationEnd {
                expanded = !expanded
                if (expanded) {
                    linear_layout_coin_selection.visible()
                }
            }
        }

        content_frame.startAnimation(animation)
    }

    private fun updateCurrencyUi(@DrawableRes leftDrawable: Int, @StringRes title: Int) {
        textview_selected_currency.run {
            text = context.getText(title).toString().toUpperCase()
            setCompoundDrawablesWithIntrinsicBounds(
                    AppCompatResources.getDrawable(context, leftDrawable),
                    null,
                    ContextCompat.getDrawable(context, R.drawable.ic_arrow_drop_down_grey600_24dp),
                    null
            )
        }
    }

    private fun closeLayout(cryptoCurrency: CryptoCurrencies) {
        // Update UI
        setCurrentlySelectedCurrency(cryptoCurrency)
        // Trigger layout change
        animateLayout(false)
        // Fade in title
        val alphaAnimation = AlphaAnimation(0.0f, 1.0f).apply { duration = 250 }
        textview_selected_currency.startAnimation(alphaAnimation)
        alphaAnimation.setAnimationListener {
            onAnimationEnd {
                textview_selected_currency.alpha = 1.0f
                // Inform parent of currency selection once animation complete to avoid glitches
                selectionListener(cryptoCurrency)
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private inner class CustomOutline internal constructor(
            internal var width: Int,
            internal var height: Int
    ) : ViewOutlineProvider() {

        override fun getOutline(view: View, outline: Outline) {
            outline.setRect(0, 0, width, height)
        }

    }

    private inner class ExpandAnimation(private val mStartHeight: Int, endHeight: Int) :
        Animation() {
        private val mDeltaHeight: Int = endHeight - mStartHeight

        override fun applyTransformation(interpolatedTime: Float, t: Transformation) {
            val lp = content_frame.layoutParams
            lp.height = (mStartHeight + mDeltaHeight * interpolatedTime).toInt()
            content_frame.layoutParams = lp
        }

        override fun willChangeBounds(): Boolean = true
    }

}