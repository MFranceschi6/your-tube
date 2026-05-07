import SwiftUI

// MARK: - SearchField

/// Reusable search text field matching the mockup design.
/// Uses a rounded rectangle background, tinted border on focus, clear button.
struct SearchField: View {
    @Binding var text: String
    var placeholder: String = "Search"
    var onSubmit: () -> Void = {}
    var onCancel: (() -> Void)?

    @FocusState private var isFocused: Bool

    var body: some View {
        HStack(spacing: Tokens.Spacing.sm) {
            field

            if isFocused {
                cancelButton
                    .transition(.move(edge: .trailing).combined(with: .opacity))
            }
        }
        .animation(.easeInOut(duration: 0.2), value: isFocused)
    }

    // MARK: Subviews

    private var field: some View {
        HStack(spacing: Tokens.Spacing.sm) {
            Image(systemName: "magnifyingglass")
                .imageScale(.medium)
                .fontWeight(.medium)
                .foregroundStyle(isFocused ? Theme.accent : Theme.onBackgroundTertiary)

            TextField(placeholder, text: $text)
                .focused($isFocused)
                .font(Theme.bodyLarge)
                .foregroundStyle(Theme.onBackground)
                .tint(Theme.accent)
                .submitLabel(.search)
                .onSubmit(onSubmit)
                .accessibilityLabel(placeholder)

            if !text.isEmpty {
                Button {
                    text = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .imageScale(.medium)
                        .foregroundStyle(Theme.onBackgroundTertiary)
                        // 44 pt tap target per iOS HIG
                        .frame(width: Tokens.HitTarget.minimum, height: Tokens.HitTarget.minimum)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Clear search")
                .transition(.opacity)
            }
        }
        .padding(.horizontal, Tokens.Spacing.md - 4)
        .frame(height: 36)
        .background(Theme.surfaceVariant, in: RoundedRectangle(cornerRadius: Tokens.Radius.sm + 2, style: Tokens.cornerStyle))
        .overlay(
            RoundedRectangle(cornerRadius: Tokens.Radius.sm + 2, style: Tokens.cornerStyle)
                .strokeBorder(
                    isFocused ? Theme.accent : Color.clear,
                    lineWidth: 1.5
                )
        )
        .animation(.easeInOut(duration: 0.2), value: isFocused)
        .animation(.easeInOut(duration: 0.15), value: text.isEmpty)
    }

    private var cancelButton: some View {
        Button {
            text = ""
            isFocused = false
            onCancel?()
        } label: {
            Text("Cancel")
                .font(Theme.bodyLarge)
                .foregroundStyle(Theme.accent)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Cancel search")
    }
}

// MARK: - Preview

#Preview("SearchField") {
    @Previewable @State var text = ""
    VStack(spacing: Tokens.Spacing.lg) {
        SearchField(text: $text, placeholder: "Search")
            .padding(.horizontal)

        SearchField(text: .constant("lofi beats"), placeholder: "Search")
            .padding(.horizontal)
    }
    .padding(.vertical)
    .background(Theme.background)
}
