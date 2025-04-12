**Mindikot: Fully Documented Game Logic**

**1. Setup**

- **1.1. Players & Teams:**
  - Requires 4 or 6 players.
  - Players are divided into two teams (Team A, Team B) seated alternatingly around the table.
    - 4 Players: Team A (Player 1, Player 3), Team B (Player 2, Player 4).
    - 6 Players: Team A (Player 1, Player 3, Player 5), Team B (Player 2, Player 4, Player 6).
- **1.2. Deck Composition:**
  - 4 Players: Standard 52-card deck (Ranks 2 through Ace in 4 suits).
  - 6 Players: 48-card deck (Standard 52-card deck with all four **Twos removed**).
- **1.3. Shuffle & Deal:**
  - The appropriate deck is shuffled thoroughly.
  - All cards are dealt clockwise, one at a time, until the deck is exhausted.
    - 4 Players: 13 cards per player.
    - 6 Players: 8 cards per player.
- **1.4. Initial Game State:**
  - `GameState.trumpSuit`: Set to `null`.
  - `GameState.trumpRevealed`: Set to `false`.
  - `GameState.hiddenCard`: Set to `null`.
  - `GameState.currentLeaderIndex`: Set to 0 (Player 1 leads the first trick, or choose randomly).
  - **Mode B (`FIRST_CARD_HIDDEN`) Specific Setup:**
    - Immediately after the deal, the player at `currentLeaderIndex` (the first leader) randomly selects one card from their hand.
    - This card is removed from their playable hand and stored securely in `GameState.hiddenCard`. Its identity is known only to the game state initially (not revealed to other players).

**2. Gameplay - Playing a Round**

- A round consists of multiple tricks, equal to the number of cards dealt to each player (13 for 4p, 8 for 6p).
- **2.1. Trick Play:**

  - **2.1.1. Leading the Trick:** The player indicated by `GameState.currentLeaderIndex` starts the trick by playing _any single card_ from their hand face-up to the center. This card establishes the `leadSuit` for the current trick.
  - **2.1.2. Following the Lead:** Play proceeds clockwise from the leader. Each subsequent player must follow these rules:
    - **A. Check Hand for Lead Suit:** Look for cards in hand matching the `leadSuit`.
    - **B. If Lead Suit Card(s) Exist (Can Follow Suit):** The player _must_ play one of the cards matching the `leadSuit`.
    - **C. If No Lead Suit Cards Exist (Cannot Follow Suit):** The player's action depends on whether the trump suit has been established (`GameState.trumpRevealed`).
      - **C.1. If `trumpRevealed == true`:** The player can play **any card** from their hand (a trump card or any other suit).
      - **C.2. If `trumpRevealed == false`:** The player cannot follow suit, and the trump is not yet set. This triggers the **Trump Selection Process** (See Section 2.2). The player's action is determined by the `GameState.gameMode`.
  - **2.1.3. Trick Completion:** A trick is complete once every player has played one card.
  - **2.1.4. Determining the Trick Winner:**
    - Identify if any cards matching the `GameState.trumpSuit` were played in the trick (only possible if `trumpRevealed == true`).
    - **If Trump Was Played:** The player who played the highest-ranking card _of the `trumpSuit`_ wins the trick.
    - **If No Trump Was Played:** The player who played the highest-ranking card _of the `leadSuit`_ wins the trick.
    - (Cards of suits other than the lead suit or the trump suit cannot win a trick).
  - **2.1.5. Collecting the Trick:** All cards played in the trick are collected by a member of the winning player's team and added to that team's `collectedCards` pile for the round.
  - **2.1.6. Next Leader:** The player who won the trick becomes the `GameState.currentLeaderIndex` and leads the next trick.

- **2.2. Trump Selection Process (Triggered when Cannot Follow Suit and `trumpRevealed == false`)**
  - **2.2.1. Mode A (`CHOOSE_WHEN_EMPTY`):**
    - **Action:** The player who cannot follow suit _must_ play a card from their hand. This card simultaneously serves as their play for the trick _and_ sets the trump suit.
    - **State Update:** `GameState.trumpSuit` is set to the `suit` of the card played. `GameState.trumpRevealed` is set to `true`.
    - **Constraint:** The player essentially chooses the trump by playing a card of that suit.
  - **2.2.2. Mode B (`FIRST_CARD_HIDDEN`):**
    - **Player Decision:** The player who cannot follow suit must choose one of two options: "Reveal" or "Pass".
    - **Option 1: Reveal**
      - **Action:** Player declares "Reveal".
      - **State Update:** `GameState.trumpSuit` is set to the `suit` of the `GameState.hiddenCard`. `GameState.trumpRevealed` is set to `true`. (The `hiddenCard` itself is not played yet).
      - **Player's Play for _this_ Trick:** Having revealed the trump, the player _must_ now play a card of this newly revealed `trumpSuit` if they have one in their hand. If they do _not_ have any cards of the `trumpSuit`, they may then play **any card** from their hand. (This obligation to play trump if possible applies _only_ during this specific reveal action).
    - **Option 2: Pass**
      - **Action:** Player declares "Pass".
      - **State Update:** `GameState.trumpSuit` remains `null`. `GameState.trumpRevealed` remains `false`.
      - **Player's Play for _this_ Trick:** Having passed, the player can play **any card** currently in their hand. This card is treated as a non-trump card, even if its suit happens to match the `hiddenCard`'s suit.

**3. End of Round**

- **3.1. Trigger:** The round ends when all tricks have been played and players have no cards left in their hands.
- **3.2. Evaluation (`RoundEvaluator.evaluateRound`):**
  - Examine the `collectedCards` pile for each team.
  - Count the number of `Rank.TEN` cards collected by each team.
  - **Kot Condition:** If one team has collected all four Tens, that team wins the round with a "Kot".
  - **Majority Condition:** If no team achieved Kot, the team that collected the majority of Tens (i.e., 2 or 3 Tens) wins the round. (A 2-2 split needs a tie-breaker rule, often related to who captured specific Tens or won the last trick, or simply a draw - Clarify if needed. Assuming majority means > 2 for now).
- **3.3. Scoring:**
  - The team that wins the round scores points (e.g., 1 point for a regular win, potentially more like 2 or 3 points for a Kot win - standard scoring rules required).
- **3.4. Starting the Next Round:**
  - Unless a game-ending condition is met (e.g., reaching a target score), a new round begins.
  - Cards are gathered, shuffled, dealt, and the game state is reset (except for scores).
  - The player who leads the first trick of the new round may rotate (e.g., the player clockwise from the previous round's first leader).

Okay, let's break down the end-to-end flow and then detail the plan for the `GameScreen`. This plan assumes the networking architecture (Host/Client) and the `GameViewModel` structure we discussed are in place.

**I. End-to-End Game Flow (Conceptual)**

1.  **App Start:** User opens the app.
2.  **Lobby Screen (`LobbyScreen.kt`):**
    - User enters their name (`viewModel.setPlayerName`).
    - User chooses "Host" or "Joiner".
    - **Host Flow:**
      - Sees configuration options (Player Count, Game Mode).
      - Selects options.
      - Taps "Create Game".
      - ViewModel: `viewModel.initializeGameSettings`, `viewModel.startServer`.
      - Navigate to `GameHostScreen.kt`.
    - **Joiner Flow:**
      - Taps "Search for Games" (Network discovery logic needed here - WiFi Direct, Bluetooth, LAN broadcast - outside current scope, assume manual IP entry for now).
      - Enters Host IP/details.
      - Taps "Join Game".
      - ViewModel: `viewModel.connectToServer(hostIp, port, playerName)`.
      - Navigate to `WaitingForPlayersScreen.kt` (or directly to Game Screen if joining late - simpler to just use Waiting).
3.  **Host Lobby Screen (`GameHostScreen.kt`):**
    - Host sees their own name and "Waiting..." slots for others.
    - Displays required player count (`viewModel.requiredPlayerCount`).
    - Observes `viewModel.connectedPlayersCount` and `viewModel.state` (to update player names as they join and send their name).
    - Displays connected player names.
    - **When `connectedPlayersCount == requiredPlayerCount`:**
      - ViewModel (Host): Automatically calls `viewModel.prepareAndBroadcastInitialState()` (deals cards, sets hidden card, broadcasts).
      - ViewModel (Host & Clients): `_gameStarted` becomes `true`.
      - Navigate to `GameScreen.kt`.
4.  **Waiting Screen (`WaitingForPlayersScreen.kt` - for Joiners):**
    - Joiner sees the lobby state (player list) received from the host via `GameState` updates.
    - Observes `viewModel.gameStarted`.
    - **When `gameStarted == true`:** Navigate to `GameScreen.kt`.
5.  **Game Screen (`GameScreen.kt` - The Core Gameplay):**
    - Displays the game board, hands, status.
    - Handles turn-based interaction based on `GameState` updates received via `viewModel.state`.
    - Players take turns playing cards or making trump decisions.
    - Tricks resolve, cards are collected (visually represented or just tracked in state).
    - **When last trick is played:**
      - ViewModel (Host): Detects empty hands, calls `RoundEvaluator.evaluateRound`.
      - ViewModel (Host): Emits `_navigateToResultScreen` event with the `RoundResult`. (Needs broadcasting mechanism so clients also navigate). Or, simpler: broadcast final GameState, clients detect end, host sends separate "RoundOver" message with result. Let's refine: Host evaluates, updates maybe a `roundWinner` field in GameState, broadcasts _that_, then navigates locally. Clients see the `roundWinner` field update and navigate.
6.  **Result Screen (`ResultScreen.kt`):**
    - Displays the `RoundResult` (Winning Team, Kot status).
    - Shows scores (ViewModel needs to accumulate scores).
    - Provides options: "Next Round" (Host triggers state reset and new deal) or "Back to Lobby".

**II. `GameScreen` - Core Concepts & Plan**

This screen is where the main interaction happens. It needs to be highly reactive to the `GameState`.

- **Data Source:** Primarily driven by `viewModel.state: StateFlow<GameState>`.
- **Key Information to Display:**
  - Other players (name, card count, active status, team).
  - Local player's hand.
  - Current trick plays (cards in the center).
  - Game status (Trump suit, Trump revealed?, Current leader/turn).
  - Team scores/tricks won this round.
- **Interaction:**
  - Only enabled for the `localPlayerId` when `gameState.awaitingInputFromPlayerIndex == localPlayerId`.
  - Input type depends on `gameState.requiredInputType`.
  - User actions call corresponding `viewModel.onCardPlayed()`, `viewModel.onRevealOrPass()`.

**III. `GameScreen` - UI Elements Breakdown**

```
+-----------------------------------------------------+
| Top Area: Game Status                               |
|  - Trump: [Suit Icon/Name] (or "Not Set")           |
|  - Tricks Won: Team A: X | Team B: Y               |
|  - Optional: Round/Game Scores                     |
+-----------------------------------------------------+
|                                                     |
| Middle Area: Player Representations & Trick Area    |
|                                                     |
|   Player 3 (Top)                                    |
|     [Name (Team Color)]                             |
|     [Card Count Icon] [Status Indicator (Turn?)]    |
|                                                     |
| Player 2 (Left)        +-------------------+        Player 4 (Right)
|   [Name]               | Trick Area        |          [Name]
|   [Cards][Status]      | - Card Played P1  |          [Cards][Status]
|                        | - Card Played P2  |
|                        | - ...             |
|                        +-------------------+
|                                                     |
| Center Bottom: Action Prompt / Trick Winner Info    |
|  - "Your Turn: [Play Card/Choose Trump/Reveal/Pass]"|
|  - "Waiting for [Player Name]..."                   |
|  - "[Player Name] wins the trick!" (Briefly)        |
|                                                     |
+-----------------------------------------------------+
| Bottom Area: Local Player                           |
|  - Hand: [CardView] [CardView] [CardView] ...       |
|  - Buttons (Conditional): [Reveal] [Pass]           |
+-----------------------------------------------------+
```

**IV. `GameScreen` - State -> UI Mapping Details**

1.  **Game Status Area:**
    - `gameState.trumpSuit`, `gameState.trumpRevealed`: Display trump icon/text. Greyed out if not revealed.
    - `gameState.tricksWon`: Display trick counts for Team 1 and Team 2.
2.  **Player Representations (Loop through `gameState.players`):**
    - Filter out `localPlayerId`.
    - Position remaining players (e.g., in a `BoxWithConstraints` using `Modifier.align` or a custom `Layout`).
    - `player.name`: Display name.
    - `player.teamId`: Set text color or add a team indicator icon/background.
    - `player.hand.size`: Display card count icon.
    - `gameState.awaitingInputFromPlayerIndex == player.id`: Highlight the active player (e.g., border, background glow).
3.  **Trick Area:**
    - `gameState.currentTrickPlays`: Iterate through this list. For each `(player, card)` pair, display the `CardView` positioned appropriately (e.g., slightly overlapping towards the center, originating near the player who played it).
4.  **Action Prompt / Winner Info:**
    - Check `gameState.awaitingInputFromPlayerIndex`:
      - If it's `localPlayerId`: Display prompt based on `gameState.requiredInputType`.
      - If it's another player ID: Display "Waiting for [Player Name]...".
      - If `null` (e.g., trick just ended): Briefly show "[Winner Name] wins the trick!" (Requires temporary state variable triggered by trick end detection in ViewModel or Composable).
5.  **Local Player Hand:**
    - Find `localPlayer` in `gameState.players`.
    - Display `localPlayer.hand` using `LazyRow` with `CardView` composables.
    - **Highlighting Valid Moves:**
      - When `gameState.awaitingInputFromPlayerIndex == localPlayerId`:
      - Get `validMoves = GameEngine.determineValidMoves(...)` (This calculation likely happens in the Composable or is derived state exposed by ViewModel).
      - Pass a flag or modifier to `CardView` if `card in validMoves`. `CardView` uses this to change appearance (e.g., border, elevation, enabled state).
      - `CardView`'s `onClick` should only call `viewModel.onCardPlayed(card)` if it's a valid move and the player's turn.
6.  **Action Buttons (Mode B - Reveal/Pass):**
    - Show Buttons `if (gameState.awaitingInputFromPlayerIndex == localPlayerId && gameState.requiredInputType == InputType.REVEAL_OR_PASS)`.
    - Button clicks call `viewModel.onRevealOrPass(Decision.REVEAL)` or `viewModel.onRevealOrPass(Decision.PASS)`.

**V. `GameScreen` - Interaction Flow (Refined)**

1.  **Screen Load/Recomposition:** Composable reads `gameState` from `viewModel.state.collectAsState()`.
2.  **UI Updates:** Any change in `gameState` triggers recomposition, updating player status, trick area, hand display, prompts, etc.
3.  **Check Local Turn:** The main interaction logic is guarded by `if (gameState.awaitingInputFromPlayerIndex == viewModel.localPlayerId)`.
4.  **Calculate Valid Moves:** Inside the `if` block, determine the valid moves based on the current `gameState`.
    ```kotlin
    val validMoves = remember(gameState) { // Recalculate only when gameState changes
        if (gameState.awaitingInputFromPlayerIndex == viewModel.localPlayerId) {
            GameEngine.determineValidMoves(
                playerHand = gameState.players.getOrNull(viewModel.localPlayerId)?.hand ?: emptyList(),
                currentTrickPlays = gameState.currentTrickPlays,
                trumpSuit = gameState.trumpSuit,
                trumpRevealed = gameState.trumpRevealed
            )
        } else {
            emptyList() // Not our turn, no valid moves to highlight
        }
    }
    ```
5.  **Render Hand:** Pass `isValidMove = card in validMoves` to each `CardView`.
6.  **Handle Input:**
    - `CardView` `onClick`: Check if `isValidMove`, if so call `viewModel.onCardPlayed(card)`.
    - `Reveal/Pass` Buttons `onClick`: Call `viewModel.onRevealOrPass(decision)`.
7.  **ViewModel Action:** The ViewModel function (`onCardPlayed` or `onRevealOrPass`) either sends the action to the host (if client) or calls `processGameInput` (if host).
8.  **State Update & Loop:** The host processes, updates the state, broadcasts. Clients receive the update. The `gameState` `StateFlow` emits, triggering recomposition, and the cycle continues for the next player.

**VI. Visual Design Considerations**

- **Clarity:** Easy to see whose turn it is, what the trump is, cards in the trick, and valid moves.
- **Responsiveness:** UI should update smoothly as the `GameState` changes.
- **Theme:** Use team colors consistently. Card designs should be clear.
- **Animations (Optional):** Card dealing, card playing to trick area, highlighting winner.

This plan provides a detailed blueprint for building the `GameScreen`. The core is reacting to the `GameState` provided by the ViewModel and enabling user interaction only at the appropriate times based on `awaitingInputFromPlayerIndex` and `requiredInputType`.
