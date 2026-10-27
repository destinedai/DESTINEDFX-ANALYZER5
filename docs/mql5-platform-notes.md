# MetaQuotes platform facts used by the terminal integration

- MetaTrader 5 for Android is described as a mobile trading application that connects to a broker's server and supports trading/analysis. Its feature list does not describe an MQL5 EA runtime. [MetaTrader 5 for Android](https://www.metatrader5.com/en/mobile-trading/android)
- MetaQuotes describes the MT5 platform as the execution module for trading robots; the MQL5 reference says programs are compiled into EX5 files in terminal directories and Expert Advisors are attached to opened charts. [Algorithmic Trading](https://www.metatrader5.com/en/automated-trading) [MQL5 Programs](https://www.mql5.com/en/docs/runtime)
- MQL5 `WebRequest` requires the exact server origin in the terminal's Expert Advisors allowed-URL list, runs synchronously, is limited to EAs and scripts, and cannot run in Strategy Tester. [WebRequest](https://www.mql5.com/en/docs/network/webrequest)
- `CTrade.Buy` returning true is not proof that a broker executed the order; MetaQuotes specifically requires checking `ResultRetcode()` and the deal ticket. [CTrade Buy](https://www.mql5.com/en/docs/standardlibrary/tradeclasses/ctrade/ctradebuy)
- `OrderCalcProfit` estimates P/L for current market conditions in the account currency and can support pre-trade risk sizing. [OrderCalcProfit](https://www.mql5.com/en/docs/trading/ordercalcprofit)

These references support the implementation/setup constraints; they do not constitute evidence that this repository's EA has compiled or executed on a broker terminal.
