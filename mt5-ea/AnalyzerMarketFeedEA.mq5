#property strict
#property version   "1.00"
#property description "DestinedFX independent read-only MT5 market feed for Analyzer"

input string InpAnalyzerFeedBaseURL = "http://127.0.0.1:8000";
input int    InpPublishSeconds = 2;
input int    InpMaxMarketWatchSymbols = 128;
input int    InpBarsPerTimeframe = 80;

string g_selectedSymbol = "";
string g_selectedTimeframe = "M15";
bool g_busy = false;

string JsonEscape(const string value)
{
   string out = "";
   for(int i = 0; i < StringLen(value); i++)
   {
      ushort ch = StringGetCharacter(value, i);
      if(ch == 34) out += "\\\"";
      else if(ch == 92) out += "\\\\";
      else if(ch >= 32) out += StringSubstr(value, i, 1);
   }
   return out;
}

bool IsAllowedLocalFeedUrl(string value)
{
   if(StringLen(value) > 0 && StringGetCharacter(value, StringLen(value) - 1) == 47)
      value = StringSubstr(value, 0, StringLen(value) - 1);
   return value == "http://127.0.0.1:8000" || value == "http://localhost:8000" ||
          value == "http://172.30.250.3:8000";
}

bool HttpRequest(const string method, const string path, const string payload, string &response)
{
   string headers = "Accept: application/json\r\n";
   char data[];
   if(method == "POST")
   {
      headers += "Content-Type: application/json\r\n";
      int copied = StringToCharArray(payload, data, 0, WHOLE_ARRAY, CP_UTF8);
      if(copied > 0) ArrayResize(data, copied - 1);
   }
   else
   {
      ArrayResize(data, 0);
   }

   char result[];
   string resultHeaders;
   ResetLastError();
   int status = WebRequest(method, InpAnalyzerFeedBaseURL + path, headers, 2500,
                           data, result, resultHeaders);
   if(status < 200 || status >= 300)
   {
      response = "";
      return false;
   }
   response = CharArrayToString(result, 0, -1, CP_UTF8);
   return true;
}

bool ReadJsonString(const string json, const string key, string &value)
{
   string needle = "\"" + key + "\"";
   int at = StringFind(json, needle);
   if(at < 0) return false;
   at = StringFind(json, ":", at + StringLen(needle));
   if(at < 0) return false;
   at++;
   while(at < StringLen(json))
   {
      ushort ch = StringGetCharacter(json, at);
      if(ch != 32 && ch != 9 && ch != 10 && ch != 13) break;
      at++;
   }
   if(at >= StringLen(json) || StringGetCharacter(json, at) != 34) return false;
   at++;
   value = "";
   bool escaped = false;
   for(int i = at; i < StringLen(json); i++)
   {
      ushort ch = StringGetCharacter(json, i);
      if(escaped)
      {
         if(ch == 34 || ch == 92 || ch == 47) value += StringSubstr(json, i, 1);
         else if(ch == 110) value += "\n";
         else if(ch == 114) value += "\r";
         else if(ch == 116) value += "\t";
         escaped = false;
         continue;
      }
      if(ch == 92) { escaped = true; continue; }
      if(ch == 34) return true;
      if(ch >= 32) value += StringSubstr(json, i, 1);
   }
   return false;
}

string ChartTimeframeLabel()
{
   ENUM_TIMEFRAMES tf = (ENUM_TIMEFRAMES)ChartPeriod(0);
   switch(tf)
   {
      case PERIOD_M1: return "M1";
      case PERIOD_M5: return "M5";
      case PERIOD_M15: return "M15";
      case PERIOD_M30: return "M30";
      case PERIOD_H1: return "H1";
      case PERIOD_H4: return "H4";
      case PERIOD_D1: return "D1";
      case PERIOD_W1: return "W1";
   }
   return "M15";
}

ENUM_TIMEFRAMES ParseTimeframe(const string label)
{
   if(label == "M1") return PERIOD_M1;
   if(label == "M5") return PERIOD_M5;
   if(label == "M15") return PERIOD_M15;
   if(label == "M30") return PERIOD_M30;
   if(label == "H1") return PERIOD_H1;
   if(label == "H4") return PERIOD_H4;
   if(label == "D1") return PERIOD_D1;
   if(label == "W1") return PERIOD_W1;
   return PERIOD_CURRENT;
}

void PollAnalyzerSelection()
{
   string response = "";
   if(!HttpRequest("GET", "/v1/analyzer/market-selection", "", response)) return;

   string symbol = "";
   string timeframe = "";
   if(ReadJsonString(response, "symbol", symbol) && symbol != "")
   {
      if(SymbolSelect(symbol, true))
         g_selectedSymbol = symbol;
   }
   if(ReadJsonString(response, "timeframe", timeframe))
   {
      if(ParseTimeframe(timeframe) != PERIOD_CURRENT)
         g_selectedTimeframe = timeframe;
   }
   if(g_selectedSymbol == "")
   {
      string current = ChartSymbol(0);
      if(current != "" && SymbolSelect(current, true))
         g_selectedSymbol = current;
   }
}

void AddSymbol(const string inputSymbol, string &symbols[], int &count, const int limit)
{
   string symbol = inputSymbol;
   StringTrimLeft(symbol);
   StringTrimRight(symbol);
   if(symbol == "" || count >= limit) return;
   for(int i = 0; i < count; i++)
      if(StringCompare(symbols[i], symbol, false) == 0) return;
   if(!SymbolSelect(symbol, true)) return;
   ArrayResize(symbols, count + 1);
   symbols[count] = symbol;
   count++;
}

int BuildSymbolList(string &symbols[])
{
   int count = 0;
   int limit = (int)MathMax(1, MathMin(128, InpMaxMarketWatchSymbols));
   ArrayResize(symbols, 0);
   AddSymbol(g_selectedSymbol, symbols, count, limit);
   int total = SymbolsTotal(true);
   for(int i = 0; i < total && count < limit; i++)
      AddSymbol(SymbolName(i, true), symbols, count, limit);
   return count;
}

string BuildSymbolsJson(const string &symbols[], const int count)
{
   string out = "[";
   for(int i = 0; i < count; i++)
   {
      if(i > 0) out += ",";
      out += "\"" + JsonEscape(symbols[i]) + "\"";
   }
   return out + "]";
}

string BuildQuotesJson(const string &symbols[], const int count, const bool terminalConnected)
{
   string out = "[";
   int emitted = 0;
   if(!terminalConnected) return out + "]";
   datetime utcNow = TimeGMT();
   datetime serverNow = TimeTradeServer();
   long serverUtcOffsetMs = (long)(serverNow - utcNow) * 1000;
   for(int i = 0; i < count; i++)
   {
      string symbol = symbols[i];
      MqlTick tick;
      if(!SymbolInfoTick(symbol, tick)) continue;
      long stamp = (long)tick.time_msc - serverUtcOffsetMs;
      int digits = (int)SymbolInfoInteger(symbol, SYMBOL_DIGITS);
      if(stamp <= 0 || digits < 0 || digits > 12 ||
         !MathIsValidNumber(tick.bid) || !MathIsValidNumber(tick.ask) ||
         tick.bid <= 0 || tick.ask < tick.bid) continue;
      if(emitted > 0) out += ",";
      out += StringFormat("{\"symbol\":\"%s\",\"bid\":%.15f,\"ask\":%.15f,\"digits\":%d,\"timestamp_ms\":%I64d}",
                          JsonEscape(symbol), tick.bid, tick.ask, digits, stamp);
      emitted++;
   }
   return out + "]";
}

string BuildCandlesJson(const bool terminalConnected)
{
   string out = "[";
   if(!terminalConnected || g_selectedSymbol == "" || !SymbolSelect(g_selectedSymbol, true))
      return out + "]";

   ENUM_TIMEFRAMES timeframes[8] =
   {
      PERIOD_M1, PERIOD_M5, PERIOD_M15, PERIOD_M30,
      PERIOD_H1, PERIOD_H4, PERIOD_D1, PERIOD_W1
   };
   string labels[8] = {"M1","M5","M15","M30","H1","H4","D1","W1"};
   datetime utcNow = TimeGMT();
   datetime serverNow = TimeTradeServer();
   long serverUtcOffsetSeconds = (long)(serverNow - utcNow);
   int requested = (int)MathMax(60, MathMin(120, InpBarsPerTimeframe));
   int emitted = 0;

   for(int ti = 0; ti < 8; ti++)
   {
      MqlRates rates[];
      ArraySetAsSeries(rates, true);
      int got = CopyRates(g_selectedSymbol, timeframes[ti], 1, requested, rates);
      if(got < 1) continue;
      for(int ri = got - 1; ri >= 0; ri--)
      {
         MqlRates bar = rates[ri];
         long stamp = ((long)bar.time - serverUtcOffsetSeconds) * 1000;
         double o = bar.open, h = bar.high, l = bar.low, c = bar.close;
         double volume = (double)bar.tick_volume;
         if(stamp <= 0 || stamp > (long)utcNow * 1000 ||
            !MathIsValidNumber(o) || !MathIsValidNumber(h) ||
            !MathIsValidNumber(l) || !MathIsValidNumber(c) ||
            !MathIsValidNumber(volume) || o <= 0 || h < MathMax(o, c) ||
            l > MathMin(o, c) || h < l || volume < 0) continue;
         if(emitted > 0) out += ",";
         out += StringFormat("{\"symbol\":\"%s\",\"timeframe\":\"%s\",\"timestamp_ms\":%I64d,\"open\":%.15f,\"high\":%.15f,\"low\":%.15f,\"close\":%.15f,\"volume\":%.0f,\"complete\":true}",
                             JsonEscape(g_selectedSymbol), labels[ti], stamp,
                             o, h, l, c, volume);
         emitted++;
      }
   }
   return out + "]";
}

string AnalyzerAccountMode()
{
   long mode = AccountInfoInteger(ACCOUNT_TRADE_MODE);
   if(mode == ACCOUNT_TRADE_MODE_DEMO) return "DEMO";
   if(mode == ACCOUNT_TRADE_MODE_REAL) return "LIVE";
   return "";
}

void PublishAnalyzerMarketData()
{
   bool terminalConnected = (TerminalInfoInteger(TERMINAL_CONNECTED) != 0);
   string accountLogin = terminalConnected ? StringFormat("%I64d", AccountInfoInteger(ACCOUNT_LOGIN)) : "";
   string accountServer = terminalConnected ? AccountInfoString(ACCOUNT_SERVER) : "";
   string accountMode = terminalConnected ? AnalyzerAccountMode() : "";
   string symbols[];
   int count = BuildSymbolList(symbols);
   string payload = StringFormat(
      "{\"terminal_connected\":%s,\"account_login\":\"%s\",\"account_server\":\"%s\",\"account_mode\":\"%s\",\"symbols\":%s,\"broker_quotes\":%s,\"broker_candles\":%s}",
      terminalConnected ? "true" : "false",
      JsonEscape(accountLogin), JsonEscape(accountServer), JsonEscape(accountMode),
      BuildSymbolsJson(symbols, count),
      BuildQuotesJson(symbols, count, terminalConnected),
      BuildCandlesJson(terminalConnected)
   );
   string response = "";
   HttpRequest("POST", "/v1/analyzer/market-heartbeat", payload, response);
}

int OnInit()
{
   if(!IsAllowedLocalFeedUrl(InpAnalyzerFeedBaseURL))
   {
      Print("Analyzer feed requires the local read-only Bridge URL: 127.0.0.1:8000 or the owner Docker bridge 172.30.250.3:8000");
      return INIT_PARAMETERS_INCORRECT;
   }
   if(InpPublishSeconds < 1 || InpPublishSeconds > 10 ||
      InpMaxMarketWatchSymbols < 1 || InpBarsPerTimeframe < 60)
      return INIT_PARAMETERS_INCORRECT;
   g_selectedSymbol = ChartSymbol(0);
   g_selectedTimeframe = ChartTimeframeLabel();
   EventSetTimer(InpPublishSeconds);
   Print("DestinedFX Analyzer market feed initialized; read-only MT5 quotes/candles, no Bot pairing or trade commands.");
   return INIT_SUCCEEDED;
}

void OnDeinit(const int reason)
{
   EventKillTimer();
}

void OnTimer()
{
   if(g_busy) return;
   g_busy = true;
   PollAnalyzerSelection();
   PublishAnalyzerMarketData();
   g_busy = false;
}

void OnTick()
{
   // All-symbol/timer publisher: deliberately no order or trade logic.
}
