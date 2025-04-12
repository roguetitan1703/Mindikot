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
