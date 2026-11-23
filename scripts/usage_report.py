"""Claude API の使用量を毎日確認し、日報をメールで送る。基準を超えたら警告にする。

- Claude に考えさせる処理はしないので、Claude の料金（トークン）はかからない。
- 使うのは Anthropic の「管理用API（Admin API）」で、使用量の数字を取りに行くだけ。
- 公開リポジトリでは Actions の記録（ログ）を誰でも見られるため、
  金額・鍵の名前・メールアドレスは一切ログに出さない。

必要な Secrets（GitHub の鍵の保管庫）:
  ANTHROPIC_ADMIN_KEY  管理用の鍵（sk-ant-admin01-...）
  MAIL_USER            送信に使うメールアカウント（例：Gmail のアドレス）
  MAIL_APP_PASSWORD    そのアカウントのアプリ用パスワード
  MAIL_TO              通知を受け取るメールアドレス
"""

import json
import os
import smtplib
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from email.mime.text import MIMEText
from email.utils import formatdate

API = "https://api.anthropic.com/v1/organizations"
API_VERSION = "2023-06-01"
JST = timezone(timedelta(hours=9))

# 100万トークンあたりの料金（米ドル）。料金が変わったらここを直す
PRICES = {
    "haiku": (0.10, 0.50),
    "sonnet": (2.00, 10.00),
    "opus": (4.00, 20.00),
    "fable": (8.00, 40.00),
}
FALLBACK = PRICES["sonnet"]  # 不明なモデルは高めに見積もる


def env(name, default=None, required=False):
    value = os.environ.get(name, default)
    if required and not value:
        sys.exit(f"設定が足りません: {name}")
    return value


ADMIN_KEY = env("ANTHROPIC_ADMIN_KEY", required=True)
DAILY_LIMIT = float(env("DAILY_LIMIT_USD", "0.50"))
MONTHLY_LIMIT = float(env("MONTHLY_LIMIT_USD", "3.00"))
YEN = float(env("YEN_PER_USD", "150"))
EXPIRY_WARN_DAYS = int(env("KEY_EXPIRY_WARN_DAYS", "7"))


def get(path, params):
    query = urllib.parse.urlencode(params, doseq=True)
    req = urllib.request.Request(
        f"{API}/{path}?{query}",
        headers={
            "x-api-key": ADMIN_KEY,
            "anthropic-version": API_VERSION,
            "User-Agent": "FridgeChef-usage-report/1.0",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as res:
            return json.load(res)
    except urllib.error.HTTPError as e:
        # 本文には鍵などは含まれないが、念のため状態コードだけ出す
        sys.exit(f"Anthropic の管理用APIでエラー（コード {e.code}）。管理用の鍵が無効になっていないか確認してください。")


def price_for(model):
    name = (model or "").lower()
    for key, price in PRICES.items():
        if key in name:
            return price
    return FALLBACK


def cost_of(result):
    p_in, p_out = price_for(result.get("model"))
    cache = result.get("cache_creation") or {}
    tokens_in = (
        (result.get("uncached_input_tokens") or 0)
        + (result.get("cache_read_input_tokens") or 0) * 0.1
        + (cache.get("ephemeral_5m_input_tokens") or 0) * 1.25
        + (cache.get("ephemeral_1h_input_tokens") or 0) * 2.0
    )
    tokens_out = result.get("output_tokens") or 0
    return tokens_in / 1_000_000 * p_in + tokens_out / 1_000_000 * p_out


def fetch_usage(start, end):
    """日ごと・鍵ごと・モデルごとの使用量を取り、{日付: {鍵ID: ドル}} にまとめる"""
    params = {
        "starting_at": start.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "ending_at": end.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "bucket_width": "1d",
        "group_by[]": ["api_key_id", "model"],
        "limit": 31,
    }
    by_day = {}
    while True:
        data = get("usage_report/messages", params)
        for bucket in data.get("data", []):
            day = bucket["starting_at"][:10]
            for r in bucket.get("results", []):
                key = r.get("api_key_id") or "（鍵なし）"
                by_day.setdefault(day, {})
                by_day[day][key] = by_day[day].get(key, 0.0) + cost_of(r)
        if not data.get("has_more"):
            break
        params["page"] = data["next_page"]
    return by_day


def fetch_keys():
    keys = {}
    params = {"limit": 100}
    while True:
        data = get("api_keys", params)
        for k in data.get("data", []):
            keys[k["id"]] = k
        if not data.get("has_more"):
            break
        params["after_id"] = data["last_id"]
    return keys


def usd(x):
    return f"${x:.3f}（約{round(x * YEN)}円）"


def main():
    now = datetime.now(timezone.utc)
    today = now.replace(hour=0, minute=0, second=0, microsecond=0)
    yesterday = today - timedelta(days=1)
    month_start = today.replace(day=1)
    start = min(month_start, today - timedelta(days=7))

    by_day = fetch_usage(start, today)
    keys = fetch_keys()

    def name(key_id):
        k = keys.get(key_id)
        return k["name"] if k else key_id

    y_str = yesterday.strftime("%Y-%m-%d")
    yesterday_by_key = by_day.get(y_str, {})
    yesterday_total = sum(yesterday_by_key.values())

    month_prefix = today.strftime("%Y-%m") if today.day > 1 else yesterday.strftime("%Y-%m")
    month_by_key = {}
    for day, per_key in by_day.items():
        if day.startswith(month_prefix):
            for k, v in per_key.items():
                month_by_key[k] = month_by_key.get(k, 0.0) + v
    month_total = sum(month_by_key.values())

    # 期限切れが近い鍵
    expiring = []
    for k in keys.values():
        if k.get("status") != "active" or not k.get("expires_at"):
            continue
        exp = datetime.fromisoformat(k["expires_at"].replace("Z", "+00:00"))
        days_left = (exp - now).days
        if days_left <= EXPIRY_WARN_DAYS:
            expiring.append((k["name"], exp.astimezone(JST).strftime("%Y-%m-%d"), days_left))

    alerts = []
    if yesterday_total > DAILY_LIMIT:
        alerts.append(f"昨日（{y_str}・世界標準時）の使用額 {usd(yesterday_total)} が、1日の基準 {usd(DAILY_LIMIT)} を超えました。")
    if month_total > MONTHLY_LIMIT:
        alerts.append(f"今月の使用額 {usd(month_total)} が、月の基準 {usd(MONTHLY_LIMIT)} を超えました。")

    lines = []
    if alerts:
        subject = "【警告】FridgeChef：Claude の使用額が基準を超えました"
        lines += ["■ 警告"] + [f"・{a}" for a in alerts] + [""]
        lines += [
            "■ やるべきこと（上から順に）",
            "1. Claude Console（platform.claude.com）にログインし、「API Keys」で心当たりのない使い方をしている鍵を「無効化（Disable）」または削除する。分からなければ全部止めてよい。",
            "2. 「Billing」で自動補充（auto-reload）がオフになっているか確認する。",
            "3. 新しい鍵を作り、アプリの設定画面に貼り直す。",
            "4. 管理用の鍵（Admin key）も作り直し、GitHub の Settings → Secrets and variables → Actions で ANTHROPIC_ADMIN_KEY を新しい値に更新する。",
            "5. GitHub の Settings → Security log（セキュリティの記録）で、身に覚えのないログインや操作がないか確認する。あればパスワードを変え、二段階認証を確認する。",
            "6. 家族が別の鍵を使っている場合は、その人にも連絡して確認する。",
            "※ 家族がたくさん使っただけ、という可能性もあります。まず落ち着いて、下の鍵ごとの内訳を見てください。",
            "",
        ]
    elif expiring:
        subject = f"【日報・鍵の期限注意】FridgeChef：昨日 {usd(yesterday_total)}／今月 {usd(month_total)}"
    else:
        subject = f"【日報】FridgeChef：昨日 {usd(yesterday_total)}／今月 {usd(month_total)}"

    if expiring:
        lines.append("■ 期限が近い鍵")
        for n, date, left in expiring:
            lines.append(f"・{n}：{date} まで（あと{max(left, 0)}日）。新しい鍵を作ってアプリに貼り直してください。")
        lines.append("")

    def breakdown(title, total, per_key):
        out = [f"■ {title}：{usd(total)}"]
        for k, v in sorted(per_key.items(), key=lambda x: -x[1]):
            out.append(f"  ・{name(k)}：{usd(v)}")
        return out + [""]

    lines += breakdown(f"昨日（{y_str}）", yesterday_total, yesterday_by_key)
    lines += breakdown("今月", month_total, month_by_key)
    lines += [
        "※ 金額は使用量（トークン）を料金表で換算した目安です。税金や料金改定は反映されません。正確な額は Claude Console で確認してください。",
        f"※ 円は 1ドル={YEN:.0f}円 で計算しています。基準額：1日 {usd(DAILY_LIMIT)}／月 {usd(MONTHLY_LIMIT)}。",
        "※ 日付の区切りは世界標準時（日本時間の朝9時）です。データの反映には数分〜数時間かかることがあります。",
    ]
    send_mail(subject, "\n".join(lines))
    print("確認しました。メールを送りました。")


def send_mail(subject, body):
    user = env("MAIL_USER", required=True)
    password = env("MAIL_APP_PASSWORD", required=True)
    to = env("MAIL_TO", required=True)
    host = env("MAIL_SMTP_HOST", "smtp.gmail.com")
    port = int(env("MAIL_SMTP_PORT", "465"))

    msg = MIMEText(body, "plain", "utf-8")
    msg["Subject"] = subject
    msg["From"] = user
    msg["To"] = to
    msg["Date"] = formatdate(localtime=False)
    try:
        with smtplib.SMTP_SSL(host, port, timeout=60) as smtp:
            smtp.login(user, password)
            smtp.send_message(msg)
    except Exception as e:  # noqa: BLE001
        # アドレスやパスワードを含めないよう、種類だけ出す
        sys.exit(f"メールを送れませんでした（{type(e).__name__}）。MAIL_ で始まる Secrets を確認してください。")


if __name__ == "__main__":
    main()
