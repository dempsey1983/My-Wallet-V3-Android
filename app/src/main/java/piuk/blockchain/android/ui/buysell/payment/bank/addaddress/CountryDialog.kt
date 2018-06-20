package piuk.blockchain.android.ui.buysell.payment.bank.addaddress

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import com.jakewharton.rxbinding2.support.v7.widget.RxSearchView
import io.reactivex.android.schedulers.AndroidSchedulers
import piuk.blockchain.android.R
import piuk.blockchain.androidcore.utils.helperfunctions.unsafeLazy
import java.util.*
import java.util.concurrent.TimeUnit
import kotlinx.android.synthetic.main.dialog_select_country.list_view_countries as listView
import kotlinx.android.synthetic.main.dialog_select_country.search_view_country as searchView


class CountryDialog(
        context: Context,
        private val listener: CountryCodeSelectionListener
) : Dialog(context) {

    private val countryCodeMap by unsafeLazy {
        Locale.getISOCountries().associateBy(
                { Locale("en", it).displayCountry },
                { it }
        ).toSortedMap()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_select_country)

        val arrayAdapter = ArrayAdapter<String>(
                context,
                android.R.layout.simple_list_item_1,
                countryCodeMap.keys.toTypedArray()
        )
        listView.adapter = arrayAdapter
        searchView.queryHint = context.getString(R.string.search_country)

        RxSearchView.queryTextChanges(searchView)
                .debounce(300, TimeUnit.MILLISECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeOn(AndroidSchedulers.mainThread())
                .doOnNext { arrayAdapter.filter.filter(it) }
                .subscribe()

        listView.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit

            override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
            ) {
                val item = parent!!.getItemAtPosition(position).toString()
                val code = countryCodeMap[item]!!
                listener.onCountryCodeSelected(code)
                dismiss()
            }
        }
    }

    interface CountryCodeSelectionListener {

        fun onCountryCodeSelected(code: String)

    }


}