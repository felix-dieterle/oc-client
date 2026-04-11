# Protocol Analysis: First Response Hang on Windows Host

## Summary
**Root Cause**: Shell prompt arrives without AI response frame, gets filtered, leaves empty chat bubble.

## Live Test Results

### Test 1: Windows Shell Prompt Only (CRITICAL)
```
Input:  PS C:\Users\felix\workspace>
Output: ""
Status: ✗ FAIL – Chat shows nothing, spinner stuck waiting
```
**Why it fails**: 
- opencode-cli sends shell prompt BEFORE AI response streaming
- Prompt is correctly filtered (not an error)
- `assistantText` becomes empty
- `flushOutput()` skips empty content: `if (result.isNotBlank())`
- Chat bubble is never created
- When AI response arrives later, it may be lost

### Test 2: Unix TUI Frames (Works Fine)
```
Input:  Deine frage: Hallo\n\nAI sagt: Hallo zurück!
Output: "Deine frage: Hallo\n\nAI sagt: Hallo zurück!"
Status: ✓ PASS – Chat displays response correctly
```
**Why it works**: AI streaming begins immediately after echo.

### Test 3-5: Protocol Parsing (All ✓ PASS)
- Echo filtering works correctly
- Multi-frame streaming handles correctly  
- Empty trailing frames don't cause content loss

## Root Cause Chain
1. Windows opencode-cli sends shell prompt `PS C:\...>` first
2. Protocol parser correctly identifies it as shell prompt (Linux regex matches)
3. Prompt is filtered (desired behavior, not an error)
4. Remaining text after filter = empty string
5. `flushOutput()` has guard: `if (result.isNotBlank())` → skips empty updates
6. No message created → app spinner waits indefinitely
7. When AI response arrives in next debounce window, it updates existing message
8. But there's no existing message ID yet → behavior undefined

## Code Location
- **Filter logic**: [OpencodeProtocol.kt](app/src/main/java/com/felix/occlient/viewmodel/OpencodeProtocol.kt)
- **Guard condition**: [ChatViewModel.kt line 283](app/src/main/java/com/felix/occlient/viewmodel/ChatViewModel.kt#L283)
  ```kotlin
  if (result.isNotBlank()) {
      // ... create/update message ...
  }
  ```

## Solution Options (Priority Order)

### Option 1: Keep Spinner State Without Message
- When shell prompt alone arrives, don't create chat bubble
- Keep `_isProcessing = true` (spinner keeps running)
- Let next non-empty frame create the message
- **Pros**: Simple, preserves current display logic
- **Cons**: UI shows spinner with nothing below it (confusing)

### Option 2: Show "Thinking..." Placeholder
- When `sawShellPrompt && _isProcessing`, show placeholder message
- Replace placeholder when real content arrives
- **Pros**: User sees activity is happening
- **Cons**: More code, requires message updates

### Option 3: Increase Debounce Before Filtering
- Wait longer before processing output (current: 500ms)
- Let multiple frames accumulate (prompt + response)
- Filter together instead of separately
- **Pros**: Avoids empty filtering entirely
- **Cons**: Higher latency, may lose incremental streaming

### Option 4: Better Frame Sequencing
- Detect when prompt arrives without response
- Hold off message creation until non-empty content
- Set a backstop timeout (e.g., 2s of empty + prompt = error)
- **Pros**: Most robust, handles edge cases
- **Cons**: Complex logic

## Recommendation
**Implement Option 2** (Show "Thinking..." Placeholder):
- Balances immediacy with clarity
- Prevents UI from appearing stuck
- Natural progression: "Thinking..." → actual response
- Already have infrastructure (`_isProcessing` flag)
