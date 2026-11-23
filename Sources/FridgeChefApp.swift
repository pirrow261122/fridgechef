import SwiftUI
import PhotosUI
import UIKit
import Security

// MARK: - アプリの入口

@main
struct FridgeChefApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

// MARK: - 鍵（APIキー）を iPhone の安全な保管場所（キーチェーン）に保存する

enum KeyStore {
    private static let service = "local.fridgechef.app"
    private static let account = "anthropic-api-key"

    static func save(_ value: String) {
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        SecItemDelete(base as CFDictionary)
        guard !value.isEmpty, let data = value.data(using: .utf8) else { return }
        var add = base
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(add as CFDictionary, nil)
    }

    static func load() -> String {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data,
              let text = String(data: data, encoding: .utf8) else { return "" }
        return text
    }
}

// MARK: - 「いつもある物」の保存

enum Staples {
    static let defaults: [String] = [
        "ご飯", "パン",
        "砂糖", "塩", "酢", "しょうゆ", "みそ",
        "油", "みりん", "酒", "だしの素", "こしょう"
    ]
    private static let key = "staples"

    static func load() -> [String] {
        guard let data = UserDefaults.standard.data(forKey: key),
              let list = try? JSONDecoder().decode([String].self, from: data) else { return defaults }
        return list
    }

    static func save(_ list: [String]) {
        if let data = try? JSONEncoder().encode(list) {
            UserDefaults.standard.set(data, forKey: key)
        }
    }
}

// MARK: - 使用額の目安（返事に含まれる使用量から計算する）

enum UsageTracker {
    /// 100万単位あたりの料金（米ドル）。料金が変わったらここを直す
    static let prices: [String: (input: Double, output: Double)] = [
        "claude-haiku-5-5": (0.10, 0.50),
        "claude-sonnet-5-5": (2.00, 10.00)
    ]

    private static let monthKey = "usageMonth"
    private static let monthUSDKey = "usageMonthUSD"
    private static let monthCallsKey = "usageMonthCalls"
    static let totalUSDKey = "usageTotalUSD"

    private static var currentMonth: String {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .gregorian)
        f.dateFormat = "yyyy-MM"
        return f.string(from: Date())
    }

    /// 月が変わっていたら今月分を0に戻す
    private static func rollOverIfNeeded() {
        let d = UserDefaults.standard
        if d.string(forKey: monthKey) != currentMonth {
            d.set(currentMonth, forKey: monthKey)
            d.set(0.0, forKey: monthUSDKey)
            d.set(0, forKey: monthCallsKey)
        }
    }

    static func record(model: String, inputTokens: Int, outputTokens: Int) {
        rollOverIfNeeded()
        let price = prices[model] ?? prices["claude-sonnet-5-5"]!
        let cost = Double(inputTokens) / 1_000_000 * price.input
                 + Double(outputTokens) / 1_000_000 * price.output
        let d = UserDefaults.standard
        d.set(d.double(forKey: monthUSDKey) + cost, forKey: monthUSDKey)
        d.set(d.integer(forKey: monthCallsKey) + 1, forKey: monthCallsKey)
        d.set(d.double(forKey: totalUSDKey) + cost, forKey: totalUSDKey)
    }

    static var monthUSD: Double { rollOverIfNeeded(); return UserDefaults.standard.double(forKey: monthUSDKey) }
    static var monthCalls: Int { rollOverIfNeeded(); return UserDefaults.standard.integer(forKey: monthCallsKey) }
    static var totalUSD: Double { UserDefaults.standard.double(forKey: totalUSDKey) }

    static func resetTotal() {
        UserDefaults.standard.set(0.0, forKey: totalUSDKey)
    }
}

// MARK: - データの形

struct Ingredient: Identifiable, Equatable {
    var id = UUID()
    var name: String
    var amount: String = ""
    var uncertain: Bool = false
    var have: Bool = true
}

struct IngredientResponse: Decodable {
    struct Item: Decodable {
        let name: String
        let amount: String?
        let uncertain: Bool?
    }
    let ingredients: [Item]
}

struct Dish: Decodable, Hashable {
    let role: String
    let name: String
    let uses: [String]?

    private enum CodingKeys: String, CodingKey { case role, name, uses }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        role = (try? c.decode(String.self, forKey: .role)) ?? ""
        name = try c.decode(String.self, forKey: .name)
        uses = try? c.decode([String].self, forKey: .uses)
    }
}

struct MenuSet: Decodable, Hashable {
    let title: String
    let dishes: [Dish]
    let minutes: Int?
    let effort: String?

    private enum CodingKeys: String, CodingKey { case title, dishes, minutes, effort }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        title = (try? c.decode(String.self, forKey: .title)) ?? "献立"
        dishes = try c.decode([Dish].self, forKey: .dishes)
        effort = try? c.decode(String.self, forKey: .effort)
        // 「40」でも「"40分"」でも読めるようにする
        if let number = try? c.decode(Int.self, forKey: .minutes) {
            minutes = number
        } else if let text = try? c.decode(String.self, forKey: .minutes) {
            minutes = Int(text.filter { $0.isNumber })
        } else {
            minutes = nil
        }
    }
}

struct MenuResponse: Decodable {
    let sets: [MenuSet]
}

enum JSONHelper {
    /// 返事の中から最初の「{」と最後の「}」の間を取り出して読み込む。崩れていたら nil
    static func decode<T: Decodable>(_ type: T.Type, from text: String) -> T? {
        guard let start = text.firstIndex(of: "{"),
              let end = text.lastIndex(of: "}"),
              start < end else { return nil }
        let json = String(text[start...end])
        return try? JSONDecoder().decode(T.self, from: Data(json.utf8))
    }
}

// MARK: - Claude を呼び出す部分

enum ClaudeError: LocalizedError {
    case noKey
    case server(String)
    case empty

    var errorDescription: String? {
        switch self {
        case .noKey: return "APIキーが未設定です。右上の歯車から設定してください。"
        case .server(let message): return "Claudeからエラーが返りました：\(message)"
        case .empty: return "返事が空でした。もう一度お試しください。"
        }
    }
}

struct ClaudeClient {
    let apiKey: String
    let model: String

    private struct ErrorBody: Decodable {
        struct Detail: Decodable { let message: String }
        let error: Detail
    }

    static func imageBlock(_ jpeg: Data) -> [String: Any] {
        let source: [String: Any] = [
            "type": "base64",
            "media_type": "image/jpeg",
            "data": jpeg.base64EncodedString()
        ]
        return ["type": "image", "source": source]
    }

    static func textBlock(_ text: String) -> [String: Any] {
        return ["type": "text", "text": text]
    }

    /// 返事の文章と、上限に達して途中で切れたかどうかを返す。
    /// tool を渡すと、Claude はそのツールの決まった形（JSON）で答え、その中身を文字にして返す
    func send(content: [[String: Any]], maxTokens: Int,
              tool: [String: Any]? = nil) async throws -> (text: String, truncated: Bool) {
        guard !apiKey.isEmpty else { throw ClaudeError.noKey }

        var request = URLRequest(url: URL(string: "https://api.anthropic.com/v1/messages")!)
        request.httpMethod = "POST"
        request.timeoutInterval = 120
        request.setValue(apiKey, forHTTPHeaderField: "x-api-key")
        request.setValue("2023-06-01", forHTTPHeaderField: "anthropic-version")
        request.setValue("application/json", forHTTPHeaderField: "content-type")

        let message: [String: Any] = ["role": "user", "content": content]
        var body: [String: Any] = [
            "model": model,
            "max_tokens": maxTokens,
            "messages": [message]
        ]
        if let tool, let name = tool["name"] as? String {
            body["tools"] = [tool]
            body["tool_choice"] = ["type": "tool", "name": name]
        }
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, response) = try await URLSession.shared.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0

        guard (200..<300).contains(status) else {
            if let err = try? JSONDecoder().decode(ErrorBody.self, from: data) {
                throw ClaudeError.server(err.error.message)
            }
            throw ClaudeError.server("通信エラー（コード \(status)）")
        }

        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw ClaudeError.empty
        }
        let usage = json["usage"] as? [String: Any]
        UsageTracker.record(model: model,
                            inputTokens: usage?["input_tokens"] as? Int ?? 0,
                            outputTokens: usage?["output_tokens"] as? Int ?? 0)
        let truncated = (json["stop_reason"] as? String) == "max_tokens"
        let blocks = json["content"] as? [[String: Any]] ?? []

        // ツールで答えた場合は、その中身（決まった形の JSON）を返す
        if let toolBlock = blocks.first(where: { ($0["type"] as? String) == "tool_use" }),
           let input = toolBlock["input"],
           JSONSerialization.isValidJSONObject(input),
           let inputData = try? JSONSerialization.data(withJSONObject: input),
           let inputText = String(data: inputData, encoding: .utf8) {
            return (inputText, truncated)
        }

        let text = blocks.compactMap { $0["text"] as? String }.joined(separator: "\n")
        guard !text.isEmpty else { throw ClaudeError.empty }
        return (text, truncated)
    }
}

// MARK: - 指示文（プロンプト）

enum Prompts {
    // Claude に決まった形で答えさせるための「ツール」の定義（Android 版と同じ内容）
    static let ingredientsTool: [String: Any] = parseTool("""
    {"name":"record_ingredients","description":"冷蔵庫の写真から読み取った食材の一覧を記録する","input_schema":{"type":"object","properties":{"ingredients":{"type":"array","items":{"type":"object","properties":{"name":{"type":"string","description":"食材名"},"amount":{"type":"string","description":"おおよその量。分からなければ空文字"},"uncertain":{"type":"boolean","description":"自信がなければ true"}},"required":["name","amount","uncertain"]}}},"required":["ingredients"]}}
    """)

    static let menusTool: [String: Any] = parseTool("""
    {"name":"record_menus","description":"夕食の献立3組を記録する","input_schema":{"type":"object","properties":{"sets":{"type":"array","description":"献立3組","items":{"type":"object","properties":{"title":{"type":"string","description":"献立の短い呼び名"},"dishes":{"type":"array","items":{"type":"object","properties":{"role":{"type":"string","enum":["主菜","副菜","汁物"]},"name":{"type":"string","description":"料理名"},"uses":{"type":"array","items":{"type":"string"},"description":"使う食材"}},"required":["role","name","uses"]}},"minutes":{"type":"integer","description":"3品全体の調理時間の目安（分）"},"effort":{"type":"string","enum":["かんたん","ふつう","手間あり"]}},"required":["title","dishes","minutes","effort"]}}},"required":["sets"]}}
    """)

    private static func parseTool(_ json: String) -> [String: Any] {
        (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any] ?? [:]
    }

    static func read(photoCount: Int) -> String {
        let head = photoCount > 1
            ? "添付は冷蔵庫の中の写真（\(photoCount)枚）です。すべての写真に写っている食材を読み取ってください。同じ食材が複数の写真に写っていたら、1つにまとめてください。"
            : "添付は冷蔵庫の中の写真です。写っている食材を読み取ってください。"
        return head + "\n" + readBody
    }

    private static let readBody = """
    結果は record_ingredients ツールで返してください。
    ルール：
    - 自信がない食材は uncertain を true にする。
    - 読み取れないものを推測で補わない。
    - 調味料のボトル類は含めなくてよい。
    - 名前は日本の家庭で使う一般的な呼び方にする（例：「豚こま肉」「木綿豆腐」）。
    """

    static func fridgeList(_ items: [Ingredient]) -> String {
        if items.isEmpty { return "（なし）" }
        return items.map { item in
            var line = item.name
            if !item.amount.isEmpty { line += "（\(item.amount)）" }
            if item.uncertain { line += "※たぶんある" }
            return line
        }.joined(separator: "、")
    }

    static func memoLine(_ memo: String) -> String {
        let trimmed = memo.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "" : "\n【メモ（家族の事情など。人数の指定があればこちらを優先）】\(trimmed)"
    }

    static func menus(ingredients: [Ingredient], staples: [String], memo: String,
                      timeLimit: Int, exclude: [String]) -> String {
        let timeRule = timeLimit > 0
            ? "- 調理時間は3品を並行して作って、全体で\(timeLimit)分以内にする。"
            : "- 調理時間の制限はない。ただし目安は必ず書く。"
        let excludeRule = exclude.isEmpty
            ? ""
            : "\n- 次の料理とは別の献立にする：\(exclude.joined(separator: "、"))"
        return """
        家庭料理に詳しい手助け役として、4人家族の夕食の献立を考えてください。

        【冷蔵庫にある食材】\(fridgeList(ingredients))
        【いつもある物（自由に使ってよい）】\(staples.joined(separator: "、"))\(memoLine(memo))

        ルール：
        - 「主菜・副菜・汁物」を1組として、3組考える。
        - 主菜がシチュー、カレー、鍋物、具だくさんのスープなど汁物を兼ねる場合は、汁物を入れない。
        - 副菜は簡単なもの（おひたし、あえ物、浅漬け、冷ややっこなど）でよい。
        - 冷蔵庫の食材をできるだけ使い切り、買い足しはなるべく少なくする。
        - 3組は和・洋・中などの系統が偏らないようにする。
        - 主食（ご飯・パン）は献立に含めない。
        \(timeRule)\(excludeRule)

        結果は record_menus ツールで3組まとめて返してください。minutes は3品全体の調理時間の目安（分）です。
        """
    }

    static func detail(set: MenuSet, ingredients: [Ingredient], staples: [String], memo: String) -> String {
        let dishes = set.dishes.map { "\($0.role)：\($0.name)" }.joined(separator: "、")
        return """
        4人家族の夕食として、次の献立の作り方を教えてください。

        【献立】\(dishes)
        【冷蔵庫にある食材】\(fridgeList(ingredients))
        【いつもある物】\(staples.joined(separator: "、"))\(memoLine(memo))

        次の見出しをそのまま使い、日本語の平易な言葉で書いてください。
        【買い足しリスト】冷蔵庫の食材といつもある物に無いものだけ。無ければ「なし」。
        【材料（4人分）】料理ごとに。
        【段取り】3品を並行して作る順番を、時間の流れに沿って。
        【作り方】料理ごとに、番号付きの手順で。
        【コツ・注意】あれば短く。
        """
    }
}

// MARK: - 「AI本体で相談する」の依頼文

enum ConsultKind {
    case threeSets          // 食材の確認画面から：献立3組を考えてもらう
    case feast              // 週末のごちそう
    case upgrade(MenuSet)   // 指定した献立を格上げ
    case centerpiece        // 主役の一品
    case genre(String)      // ジャンル指定

    static let genres = ["和食", "洋食", "中華", "エスニック"]
}

extension Prompts {
    static func consult(kind: ConsultKind, ingredients: [Ingredient], staples: [String],
                        memo: String) -> String {
        let common = """
        【冷蔵庫にある食材】\(fridgeListPublic(ingredients))
        【いつもある物】\(staples.joined(separator: "、"))
        【人数】4人家族\(memoLinePublic(memo))
        """
        let request: String
        switch kind {
        case .threeSets:
            request = """
            冷蔵庫の食材を使って、4人家族の夕食の献立を3組考えてください。
            - 「主菜・副菜・汁物」を1組とする。主菜がシチュー、カレー、鍋物など汁物を兼ねる場合は汁物は不要。
            - 副菜は簡単なもの（おひたし、あえ物、浅漬けなど）でよい。
            - 冷蔵庫の食材をできるだけ使い切り、買い足しはなるべく少なくする。
            - 3組は和・洋・中などの系統が偏らないようにする。
            各組について、料理名、使う食材、調理時間の目安、買い足しリストを書いてください。気に入った組があれば、続けて作り方を聞きます。
            """
        case .feast:
            request = """
            週末に時間と手間をかけて作る、お店のような本格的なごちそう献立（主菜・副菜・汁物）を考えてください。
            冷蔵庫の食材をできるだけ生かし、足りない物は買い足しリストにまとめてください。
            下ごしらえから仕上げまでの段取りを時間の流れで示し、各料理の材料（4人分）と作り方を書いてください。
            """
        case .upgrade(let set):
            let dishes = set.dishes.map { "\($0.role)：\($0.name)" }.joined(separator: "、")
            request = """
            次の献立を、家庭で一段おいしく、見た目も華やかにする方法を教えてください。
            【献立】\(dishes)
            料理ごとに、隠し味、火加減や下ごしらえのコツ、盛り付けの工夫を具体的に書いてください。
            追加で必要な食材があれば、買い足しリストにまとめてください。
            """
        case .centerpiece:
            request = """
            冷蔵庫の食材を生かして、食卓の主役になる手の込んだ一品を考えてください。
            それに合う、手間の少ない副菜も1〜2品添えてください。
            材料（4人分）、作り方、うまく作るコツ、買い足しリストを書いてください。
            """
        case .genre(let name):
            request = """
            \(name)で、少し凝った献立（主菜・副菜・汁物）を考えてください。主菜が汁物を兼ねる場合は汁物は不要です。
            冷蔵庫の食材をできるだけ生かし、材料（4人分）、作り方、段取り、買い足しリストを書いてください。
            """
        }
        return request + "\n\n" + common + "\n\n主食（ご飯・パン）は献立に含めなくてかまいません。日本の家庭で手に入る食材・道具で作れる内容にしてください。"
    }

    static func fridgeListPublic(_ items: [Ingredient]) -> String { fridgeList(items) }
    static func memoLinePublic(_ memo: String) -> String { memoLine(memo) }
}

// MARK: - 画像の縮小（送る量を減らして料金を抑える）

extension UIImage {
    func jpegForUpload(maxSide: CGFloat = 1568, quality: CGFloat = 0.7) -> Data? {
        let longest = max(size.width, size.height)
        let ratio = longest > maxSide ? maxSide / longest : 1
        let target = CGSize(width: floor(size.width * ratio), height: floor(size.height * ratio))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let resized = UIGraphicsImageRenderer(size: target, format: format).image { _ in
            self.draw(in: CGRect(origin: .zero, size: target))
        }
        return resized.jpegData(compressionQuality: quality)
    }
}

// MARK: - 画面の流れと処理をまとめる部分

@MainActor
final class AppModel: ObservableObject {
    enum Step { case photo, confirm, menus, detail }

    @Published var step: Step = .photo
    @Published var images: [UIImage] = []
    static let maxPhotos = 5

    func addImage(_ image: UIImage) {
        guard images.count < AppModel.maxPhotos else { return }
        images.append(image)
    }
    @Published var memo = ""
    @Published var ingredients: [Ingredient] = []
    @Published var sets: [MenuSet] = []
    @Published var selected: MenuSet?
    @Published var detailText = ""
    @Published var rawText: String?
    @Published var isLoading = false
    @Published var errorMessage: String?
    static let readFailedNote = "うまく食材を読み取れませんでした。もう一度押すか、「写真なしで食材を手入力する」で入力してください。"
    static let menusFailedNote = "うまく献立を作れませんでした。もう一度押してください。"

    @Published var monthUSD: Double = UsageTracker.monthUSD
    @Published var totalUSD: Double = UsageTracker.totalUSD

    func refreshUsage() {
        monthUSD = UsageTracker.monthUSD
        totalUSD = UsageTracker.totalUSD
    }

    private var client: ClaudeClient {
        ClaudeClient(apiKey: KeyStore.load(),
                     model: UserDefaults.standard.string(forKey: "model") ?? "claude-haiku-5-5")
    }

    private var timeLimit: Int { UserDefaults.standard.integer(forKey: "timeLimit") }

    private var usableIngredients: [Ingredient] {
        ingredients.filter { $0.have && !$0.name.trimmingCharacters(in: .whitespaces).isEmpty }
    }

    private func run(_ work: @MainActor () async throws -> Void) async {
        errorMessage = nil
        isLoading = true
        defer {
            isLoading = false
            refreshUsage()
        }
        do {
            try await work()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// 1段階目：写真から食材を読み取る。thenMenus が true なら確認を飛ばして献立まで進む
    func readIngredients(thenMenus: Bool) async {
        let jpegs = images.compactMap { $0.jpegForUpload() }
        guard !jpegs.isEmpty else { return }
        await run {
            let content = jpegs.map { ClaudeClient.imageBlock($0) }
                + [ClaudeClient.textBlock(Prompts.read(photoCount: jpegs.count))]
            // 読み込めなかったら1回だけ自動でやり直す
            var parsed: IngredientResponse?
            for _ in 0..<2 {
                let reply = try await client.send(content: content, maxTokens: 4000,
                                                  tool: Prompts.ingredientsTool)
                if !reply.truncated,
                   let result = JSONHelper.decode(IngredientResponse.self, from: reply.text) {
                    parsed = result
                    break
                }
            }
            guard let parsed else {
                errorMessage = AppModel.readFailedNote
                return
            }
            rawText = nil
            ingredients = parsed.ingredients.map {
                Ingredient(name: $0.name, amount: $0.amount ?? "", uncertain: $0.uncertain ?? false)
            }
            step = .confirm
            if thenMenus {
                try await fetchMenus(exclude: [])
            }
        }
    }

    /// 2段階目：確定した食材で献立3組を出す。regenerate が true なら今の3組とは別の案にする
    func makeMenus(regenerate: Bool) async {
        let exclude = regenerate ? sets.flatMap { $0.dishes.map { $0.name } } : []
        await run {
            try await fetchMenus(exclude: exclude)
        }
    }

    private func fetchMenus(exclude: [String]) async throws {
        let prompt = Prompts.menus(ingredients: usableIngredients, staples: Staples.load(),
                                   memo: memo, timeLimit: timeLimit, exclude: exclude)
        // 読み込めなかったら1回だけ自動でやり直す。それでも駄目なら記号は見せずに案内だけ出す
        for _ in 0..<2 {
            let reply = try await client.send(content: [ClaudeClient.textBlock(prompt)], maxTokens: 6000,
                                              tool: Prompts.menusTool)
            if !reply.truncated,
               let parsed = JSONHelper.decode(MenuResponse.self, from: reply.text),
               !parsed.sets.isEmpty {
                sets = parsed.sets
                rawText = nil
                step = .menus
                return
            }
        }
        errorMessage = AppModel.menusFailedNote
    }

    /// 選んだ1組だけ、作り方と買い足しリストを詳しく出す
    func makeDetail(_ set: MenuSet) async {
        selected = set
        await run {
            let prompt = Prompts.detail(set: set, ingredients: usableIngredients,
                                        staples: Staples.load(), memo: memo)
            let reply = try await client.send(content: [ClaudeClient.textBlock(prompt)], maxTokens: 8000)
            detailText = reply.truncated
                ? reply.text + "\n\n（返事が長すぎて途中で切れました。もう一度この組を選び直してください）"
                : reply.text
            step = .detail
        }
    }

    /// 「AI本体で相談する」用の依頼文を作る（Claudeの鍵は使わない）
    func consultPrompt(_ kind: ConsultKind) -> String {
        Prompts.consult(kind: kind, ingredients: usableIngredients, staples: Staples.load(),
                        memo: memo)
    }

    func addIngredient(_ name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        ingredients.append(Ingredient(name: trimmed))
    }

    func back() {
        errorMessage = nil
        switch step {
        case .photo: break
        case .confirm: step = .photo
        case .menus: step = .confirm
        case .detail: step = .menus
        }
    }

    func reset() {
        step = .photo
        images = []
        memo = ""
        ingredients = []
        sets = []
        selected = nil
        detailText = ""
        rawText = nil
        errorMessage = nil
    }
}

// MARK: - カメラ画面

struct CameraPicker: UIViewControllerRepresentable {
    let onPick: (UIImage) -> Void
    @Binding var isPresented: Bool

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: CameraPicker
        init(_ parent: CameraPicker) { self.parent = parent }

        func imagePickerController(_ picker: UIImagePickerController,
                                   didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let picked = info[.originalImage] as? UIImage {
                parent.onPick(picked)
            }
            parent.isPresented = false
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            parent.isPresented = false
        }
    }
}

// MARK: - 設定画面

struct SettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var keyInput: String = KeyStore.load()
    @State private var staples: [String] = Staples.load()
    @State private var newStaple = ""
    @AppStorage("model") private var model: String = "claude-haiku-5-5"
    @AppStorage("timeLimit") private var timeLimit: Int = 0
    @AppStorage("depositUSD") private var depositUSD: Double = 0
    @AppStorage("yenPerDollar") private var yenPerDollar: Double = 150
    @State private var confirmReset = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    ForEach(staples, id: \.self) { item in
                        Text(item)
                    }
                    .onDelete { staples.remove(atOffsets: $0) }

                    HStack {
                        TextField("追加する物", text: $newStaple)
                        Button("追加") {
                            let trimmed = newStaple.trimmingCharacters(in: .whitespacesAndNewlines)
                            if !trimmed.isEmpty && !staples.contains(trimmed) {
                                staples.append(trimmed)
                            }
                            newStaple = ""
                        }
                    }

                    Button("初期の内容に戻す") { staples = Staples.defaults }
                } header: {
                    Text("いつもある物")
                } footer: {
                    Text("ここに入れた物は毎回「ある」として扱い、買い足しリストに出ません。左にスワイプで削除できます。")
                }

                Section {
                    Picker("調理時間", selection: $timeLimit) {
                        Text("指定なし").tag(0)
                        Text("30分以内").tag(30)
                        Text("60分以内").tag(60)
                        Text("90分以内").tag(90)
                    }
                } header: {
                    Text("調理時間")
                } footer: {
                    Text("3品を並行して作ったときの全体の時間です。献立は4人分で考えます。")
                }

                Section {
                    Picker("モデル", selection: $model) {
                        Text("Haiku（安い・速い）").tag("claude-haiku-5-5")
                        Text("Sonnet（読み取りが丁寧）").tag("claude-sonnet-5-5")
                    }
                    .pickerStyle(.inline)
                } header: {
                    Text("使うモデル")
                } footer: {
                    Text("まずは Haiku で試し、食材の読み違いが多ければ Sonnet に切り替えてください。")
                }

                Section {
                    HStack {
                        Text("入金した合計（ドル）")
                        Spacer()
                        TextField("5", value: $depositUSD, format: .number)
                            .keyboardType(.decimalPad)
                            .multilineTextAlignment(.trailing)
                            .frame(width: 90)
                    }
                    HStack {
                        Text("1ドルあたりの円")
                        Spacer()
                        TextField("150", value: $yenPerDollar, format: .number)
                            .keyboardType(.decimalPad)
                            .multilineTextAlignment(.trailing)
                            .frame(width: 90)
                    }
                    HStack {
                        Text("これまでの使用額（目安）")
                        Spacer()
                        Text("$\(String(format: "%.3f", UsageTracker.totalUSD))")
                            .foregroundColor(.secondary)
                    }
                    Button("使用額の累計を0に戻す", role: .destructive) { confirmReset = true }
                } header: {
                    Text("使用額の目安")
                } footer: {
                    Text("Claude からの返事に含まれる使用量を、料金表で換算した目安です。税金や料金改定は反映されません。買い足したら「入金した合計」を増やしてください。ずれが大きいときは、Claude Console の残高を「入金した合計」に入れ、累計を0に戻すと合わせられます。")
                }

                Section {
                    SecureField("sk-ant-...", text: $keyInput)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } header: {
                    Text("APIキー")
                } footer: {
                    Text("キーはこのiPhoneの中（キーチェーン）にだけ保存され、コードや GitHub には残りません。")
                }
            }
            .navigationTitle("設定")
            .confirmationDialog("使用額の累計を0に戻しますか？", isPresented: $confirmReset, titleVisibility: .visible) {
                Button("0に戻す", role: .destructive) { UsageTracker.resetTotal() }
            }
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        KeyStore.save(keyInput.trimmingCharacters(in: .whitespacesAndNewlines))
                        Staples.save(staples)
                        dismiss()
                    }
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button("閉じる") { dismiss() }
                }
            }
        }
    }
}

// MARK: - 使用額の目安の表示

struct UsageBar: View {
    @ObservedObject var model: AppModel
    @AppStorage("depositUSD") private var depositUSD: Double = 0
    @AppStorage("yenPerDollar") private var yenPerDollar: Double = 150

    private func yen(_ usd: Double) -> String {
        "約¥\(Int((usd * yenPerDollar).rounded()))"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("今月の使用額（目安）：\(yen(model.monthUSD))（$\(String(format: "%.3f", model.monthUSD))）")
            if depositUSD > 0 {
                let remaining = depositUSD - model.totalUSD
                Text("残高（目安）：$\(String(format: "%.2f", remaining))（\(yen(remaining))）")
                    .foregroundColor(remaining < 1 ? .orange : .secondary)
                if remaining < 1 {
                    Text("残りが1ドルを切りました。Claude Console で実際の残高を確認してください。")
                        .foregroundColor(.orange)
                }
            } else {
                Text("設定で入金額を入れると、残高の目安も表示します。")
            }
        }
        .font(.caption)
        .foregroundColor(.secondary)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 10).fill(Color.gray.opacity(0.1)))
    }
}

// MARK: - 1. 写真の画面

struct PhotoStepView: View {
    @ObservedObject var model: AppModel
    @Binding var showCamera: Bool
    @State private var pickerItems: [PhotosPickerItem] = []

    private var remaining: Int { AppModel.maxPhotos - model.images.count }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                UsageBar(model: model)

                if model.images.isEmpty {
                    RoundedRectangle(cornerRadius: 12)
                        .fill(Color.gray.opacity(0.15))
                        .frame(height: 160)
                        .overlay(Text("冷蔵庫の写真がまだありません").foregroundStyle(.secondary))
                } else {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 10) {
                            ForEach(Array(model.images.enumerated()), id: \.offset) { index, image in
                                Image(uiImage: image)
                                    .resizable()
                                    .scaledToFill()
                                    .frame(width: 140, height: 190)
                                    .clipShape(RoundedRectangle(cornerRadius: 12))
                                    .overlay(alignment: .topTrailing) {
                                        Button {
                                            model.images.remove(at: index)
                                        } label: {
                                            Image(systemName: "xmark.circle.fill")
                                                .font(.title2)
                                                .symbolRenderingMode(.palette)
                                                .foregroundStyle(Color.white, Color.black.opacity(0.6))
                                        }
                                        .padding(6)
                                    }
                            }
                        }
                    }
                }

                Text("写真 \(model.images.count) / \(AppModel.maxPhotos) 枚（冷蔵室・野菜室・冷凍室・扉のポケットなどを分けて撮れます）")
                    .font(.caption)
                    .foregroundStyle(.secondary)

                HStack {
                    Button {
                        if UIImagePickerController.isSourceTypeAvailable(.camera) {
                            showCamera = true
                        } else {
                            model.errorMessage = "この端末ではカメラが使えません。"
                        }
                    } label: {
                        Label(model.images.isEmpty ? "撮影する" : "もう1枚撮る", systemImage: "camera")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .disabled(remaining <= 0)

                    PhotosPicker(selection: $pickerItems,
                                 maxSelectionCount: max(remaining, 1),
                                 matching: .images) {
                        Label("写真を選ぶ", systemImage: "photo").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .disabled(remaining <= 0)
                }

                TextField("メモ（例：今日は6人分、魚は避けたい）", text: $model.memo, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
                    .lineLimit(1...4)

                Button {
                    Task { await model.readIngredients(thenMenus: false) }
                } label: {
                    Text("食材を読み取って確認する")
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                }
                .buttonStyle(.borderedProminent)
                .disabled(model.images.isEmpty || model.isLoading)

                Button {
                    Task { await model.readIngredients(thenMenus: true) }
                } label: {
                    Text("確認せずにそのまま献立へ")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .disabled(model.images.isEmpty || model.isLoading)

                Button {
                    model.ingredients = []
                    model.rawText = nil
                    model.step = .confirm
                } label: {
                    Text("写真なしで食材を手入力する")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderless)
                .disabled(model.isLoading)

                Text("※写真は Claude を作っている会社（Anthropic）に送られます。郵便物や書類が写り込まないように撮ってください。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding()
        }
        .onChange(of: pickerItems) { items in
            guard !items.isEmpty else { return }
            Task {
                for item in items {
                    if let data = try? await item.loadTransferable(type: Data.self),
                       let picked = UIImage(data: data) {
                        model.addImage(picked)
                    }
                }
                pickerItems = []
            }
        }
    }
}

// MARK: - 2. 食材の確認画面

struct ConfirmStepView: View {
    @ObservedObject var model: AppModel
    @State private var newName = ""

    var body: some View {
        List {
            Section {
                ForEach($model.ingredients) { $item in
                    HStack(alignment: .center, spacing: 10) {
                        Toggle("ある", isOn: $item.have)
                            .labelsHidden()
                        VStack(alignment: .leading, spacing: 2) {
                            TextField("食材名", text: $item.name)
                                .foregroundColor(item.have ? .primary : .secondary)
                            TextField("量（任意。例：4個、半分）", text: $item.amount)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        if item.uncertain {
                            Button {
                                item.uncertain = false
                            } label: {
                                Text("要確認")
                                    .font(.caption.bold())
                                    .foregroundStyle(.orange)
                            }
                            .buttonStyle(.borderless)
                        }
                    }
                }
                .onDelete { model.ingredients.remove(atOffsets: $0) }

                HStack {
                    TextField("食材を追加（例：卵）", text: $newName)
                        .onSubmit { addNew() }
                    Button("追加") { addNew() }
                }
            } header: {
                Text("冷蔵庫にある食材")
            } footer: {
                Text("スイッチを切ると「ない」扱いになります。オレンジの「要確認」は、写真から自信をもって読み取れなかった物です。確かめたら押して消してください。左にスワイプで削除できます。")
            }

            Section {
                Text(Staples.load().joined(separator: "、"))
                    .font(.callout)
                    .foregroundStyle(.secondary)
            } header: {
                Text("いつもある物（自動で加えます）")
            }

            Section {
                Button {
                    Task { await model.makeMenus(regenerate: false) }
                } label: {
                    Text("献立を3組出す")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(model.isLoading)
            }

            Section {
                AIConsultButton(model: model, mode: .confirm)
            } footer: {
                Text("確認した食材で、AI のアプリに献立3組を考えてもらう依頼文を作ります。このアプリの料金はかかりません。")
            }
        }
    }

    private func addNew() {
        model.addIngredient(newName)
        newName = ""
    }
}

// MARK: - 「AI本体で相談する」ボタンと画面

struct ConsultDraft: Identifiable {
    let id = UUID()
    var text: String
}

struct AIConsultButton: View {
    enum Mode { case confirm, menus, detail }

    @ObservedObject var model: AppModel
    let mode: Mode
    @State private var showChoices = false
    @State private var draft: ConsultDraft?

    private let letters = ["A", "B", "C", "D", "E"]

    var body: some View {
        Button {
            if mode == .confirm {
                open(.threeSets)
            } else {
                showChoices = true
            }
        } label: {
            Label("AI本体で相談する", systemImage: "bubble.left.and.text.bubble.right")
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.bordered)
        .disabled(model.isLoading)
        .confirmationDialog("どんな相談をしますか？", isPresented: $showChoices, titleVisibility: .visible) {
            if mode == .menus {
                ForEach(Array(model.sets.enumerated()), id: \.offset) { index, set in
                    Button(upgradeLabel(index: index, set: set)) {
                        open(.upgrade(set))
                    }
                }
            }
            if mode == .detail, let set = model.selected {
                Button("選んだ献立を格上げ") { open(.upgrade(set)) }
            }
            Button("週末のごちそう") { open(.feast) }
            Button("主役の一品") { open(.centerpiece) }
            ForEach(ConsultKind.genres, id: \.self) { genre in
                Button("ジャンル指定：\(genre)") { open(.genre(genre)) }
            }
            Button("やめる", role: .cancel) {}
        } message: {
            Text("依頼文を作ってコピーします。Claude、ChatGPT、Gemini などのアプリに貼り付けて使えます。このアプリの料金はかかりません。")
        }
        .sheet(item: $draft) { item in
            ConsultSheet(text: item.text)
        }
    }

    private func upgradeLabel(index: Int, set: MenuSet) -> String {
        let letter = index < letters.count ? letters[index] : String(index + 1)
        return letter + "案「" + set.title + "」を格上げ"
    }

    private func open(_ kind: ConsultKind) {
        let text = model.consultPrompt(kind)
        UIPasteboard.general.string = text
        draft = ConsultDraft(text: text)
    }
}

struct ConsultSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State var text: String
    @State private var copied = true

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 12) {
                Text(copied ? "コピーしました。下の「共有」からAIのアプリを選ぶか、アプリを開いて貼り付けてください。文は直してから送ることもできます。" : "文を直したら、もう一度コピーしてください。")
                    .font(.caption)
                    .foregroundStyle(.secondary)

                TextEditor(text: $text)
                    .font(.callout)
                    .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.gray.opacity(0.4)))
                    .onChange(of: text) { _ in copied = false }

                HStack {
                    Button {
                        UIPasteboard.general.string = text
                        copied = true
                    } label: {
                        Label(copied ? "コピー済み" : "コピーする", systemImage: "doc.on.doc")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)

                    ShareLink(item: text) {
                        Label("共有", systemImage: "square.and.arrow.up")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                }
            }
            .padding()
            .navigationTitle("AI本体で相談する")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("閉じる") { dismiss() }
                }
            }
        }
    }
}

// MARK: - 3. 献立3組の画面

struct MenusStepView: View {
    @ObservedObject var model: AppModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {

                ForEach(Array(model.sets.enumerated()), id: \.offset) { index, set in
                    Button {
                        Task { await model.makeDetail(set) }
                    } label: {
                        MenuCard(index: index, set: set)
                    }
                    .buttonStyle(.plain)
                    .disabled(model.isLoading)
                }

                if !model.sets.isEmpty {
                    Text("気に入った組を押すと、作り方と買い足しリストが出ます。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Button {
                    Task { await model.makeMenus(regenerate: true) }
                } label: {
                    Label("別の3組を出し直す", systemImage: "arrow.clockwise")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .disabled(model.isLoading)

                AIConsultButton(model: model, mode: .menus)
            }
            .padding()
        }
    }
}

struct MenuCard: View {
    let index: Int
    let set: MenuSet

    private var label: String {
        let letters = ["A", "B", "C", "D", "E"]
        return index < letters.count ? letters[index] : "\(index + 1)"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("\(label)案：\(set.title)")
                .font(.headline)

            ForEach(set.dishes, id: \.self) { dish in
                VStack(alignment: .leading, spacing: 2) {
                    Text("\(dish.role)　\(dish.name)")
                    if let uses = dish.uses, !uses.isEmpty {
                        Text("使う食材：" + uses.joined(separator: "、"))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }

            HStack(spacing: 12) {
                if let minutes = set.minutes {
                    Label("約\(minutes)分", systemImage: "clock")
                }
                if let effort = set.effort {
                    Label(effort, systemImage: "hand.raised")
                }
            }
            .font(.caption)
            .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(
            RoundedRectangle(cornerRadius: 12)
                .stroke(Color.gray.opacity(0.4))
        )
        .contentShape(Rectangle())
    }
}

// MARK: - 4. 作り方の画面

struct DetailStepView: View {
    @ObservedObject var model: AppModel
    @State private var copied = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if let set = model.selected {
                    Text(set.title).font(.title3.bold())
                    Text(set.dishes.map { "\($0.role)：\($0.name)" }.joined(separator: "\n"))
                        .foregroundStyle(.secondary)
                    Divider()
                }

                Text(model.detailText)
                    .textSelection(.enabled)

                HStack {
                    Button {
                        UIPasteboard.general.string = model.detailText
                        copied = true
                    } label: {
                        Label(copied ? "コピーしました" : "全文コピー", systemImage: "doc.on.doc")
                    }
                    .buttonStyle(.bordered)

                    Button {
                        model.reset()
                    } label: {
                        Label("最初からやり直す", systemImage: "house")
                    }
                    .buttonStyle(.bordered)
                }

                AIConsultButton(model: model, mode: .detail)

                Text("※食材の読み取りや作り方は間違うことがあります。傷み具合や火の通りは、最後にご自身の目で確かめてください。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding()
        }
    }
}

// MARK: - 全体の画面

struct ContentView: View {
    @StateObject private var model = AppModel()
    @State private var showCamera = false
    @State private var showSettings = false

    private var title: String {
        switch model.step {
        case .photo: return "冷蔵庫シェフ"
        case .confirm: return "食材の確認"
        case .menus: return "献立3組"
        case .detail: return "作り方"
        }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if let message = model.errorMessage {
                    Text(message)
                        .font(.callout)
                        .foregroundStyle(.red)
                        .padding(.horizontal)
                        .padding(.vertical, 6)
                }

                switch model.step {
                case .photo:
                    PhotoStepView(model: model, showCamera: $showCamera)
                case .confirm:
                    ConfirmStepView(model: model)
                case .menus:
                    MenusStepView(model: model)
                case .detail:
                    DetailStepView(model: model)
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    if model.step != .photo {
                        Button {
                            model.back()
                        } label: {
                            Label("戻る", systemImage: "chevron.left")
                        }
                        .disabled(model.isLoading)
                    }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button { showSettings = true } label: { Image(systemName: "gearshape") }
                }
            }
            .overlay {
                if model.isLoading {
                    ZStack {
                        Color.black.opacity(0.15).ignoresSafeArea()
                        ProgressView("考えています…")
                            .padding()
                            .background(RoundedRectangle(cornerRadius: 12).fill(Color(.systemBackground)))
                    }
                }
            }
            .sheet(isPresented: $showSettings, onDismiss: { model.refreshUsage() }) { SettingsView() }
            .fullScreenCover(isPresented: $showCamera) {
                CameraPicker(onPick: { model.addImage($0) }, isPresented: $showCamera).ignoresSafeArea()
            }
        }
    }
}
