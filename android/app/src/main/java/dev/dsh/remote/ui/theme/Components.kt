package dev.dsh.remote.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.dsh.remote.ui.icons.DshIcon
import dev.dsh.remote.ui.icons.DshIcons

/**
 * 设置分组卡:标题 + 说明 + 若干行,整块是玻璃卡。
 *
 * 这是设置页的主要骨架:一块卡装一组相关的开关 / 跳转,卡片之间靠 24dp 留白,
 * 不画分割线。
 */
@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val sem = LocalDshSemantic.current
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onSurface,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
            )
        }
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = sem.textTertiary,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
        }
        GlassSurface(
            tier = GlassTier.Card,
            shape = RoundedCornerShape(AppShape.lg),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(vertical = 4.dp)) { content() }
        }
    }
}

/**
 * 一行设置:左图标 + 标题/说明 + 右侧内容(开关、箭头、数值)。
 *
 * 整行可点,按压缩放;右侧内容自己处理点击时给 [onClick] 传 null。
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: Int? = null,
    tint: Color? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val sem = LocalDshSemantic.current
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.pressableClick(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            IconBadge(icon = icon, tint = tint ?: scheme.primary)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = sem.textTertiary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** 行首的小图标块:圆角方块 + 淡色底。 */
@Composable
fun IconBadge(
    icon: Int,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    size: Dp = 30.dp,
) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(AppShape.sm))
            .background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        DshIcon(icon, tint = tint, size = (size.value * 0.58f).dp)
    }
}

/** 分组之间 / 分组内的空行。 */
@Composable
fun SectionGap() {
    Spacer(Modifier.height(20.dp))
}

/**
 * 胶囊按钮:玻璃胶囊 + 主色文字。用于次要操作(「全部已读」「切换」)。
 */
@Composable
fun GlassPill(
    text: String,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val sem = LocalDshSemantic.current
    val fill = if (selected) scheme.primary.copy(alpha = 0.14f) else Color.Transparent
    GlassSurface(
        tier = GlassTier.Pill,
        shape = CircleShape,
        modifier = modifier,
        style = LocalDshSurfaces.current.pill.let { style ->
            style.copy(
                fill = if (selected) fill else style.fill,
                fallbackFill = if (selected) fill else style.fallbackFill,
                hairline = if (selected) scheme.primary.copy(alpha = 0.35f) else style.hairline,
            )
        },
        onClick = if (enabled) onClick else null,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) {
                DshIcon(
                    icon,
                    tint = if (selected) scheme.primary else sem.textTertiary,
                    size = 14.dp,
                )
            }
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = when {
                    !enabled -> sem.textDisabled
                    selected -> scheme.primary
                    else -> scheme.onSurfaceVariant
                },
            )
        }
    }
}

/**
 * 主按钮:主色实心 + 连续圆角(不是胶囊)。按下换更深一档,不只靠水波。
 */
@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: Int? = null,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val sem = LocalDshSemantic.current
    val bg = if (enabled) scheme.primary else scheme.primary.copy(alpha = 0.38f)
    val fg = if (enabled) scheme.onPrimary else scheme.onPrimary.copy(alpha = 0.75f)
    Box(
        modifier
            .height(50.dp)
            .clip(RoundedCornerShape(AppShape.md))
            .background(bg)
            .then(if (enabled) Modifier.pressableClick(onClick = onClick) else Modifier)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) DshIcon(icon, tint = fg, size = 18.dp)
            Text(text, style = MaterialTheme.typography.labelLarge, color = fg)
        }
    }
}

/**
 * 次按钮:玻璃 + 发丝描边。禁用时描边减半。
 */
@Composable
fun SecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: Int? = null,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val sem = LocalDshSemantic.current
    val surfaces = LocalDshSurfaces.current
    GlassSurface(
        tier = GlassTier.Card,
        shape = RoundedCornerShape(AppShape.md),
        modifier = modifier.height(50.dp),
        style = surfaces.card.copy(
            hairline = scheme.outline.copy(alpha = if (enabled) 0.4f else 0.2f),
        ),
        onClick = if (enabled) onClick else null,
    ) {
        Row(
            Modifier.padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) {
                DshIcon(icon, tint = if (enabled) scheme.onSurface else sem.textDisabled, size = 18.dp)
            }
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) scheme.onSurface else sem.textDisabled,
            )
        }
    }
}

/**
 * 空状态:大字 + 一行说明 + 可选操作。居中的留白比插画安静。
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: Int? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val sem = LocalDshSemantic.current
    Column(
        modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(scheme.primary.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                DshIcon(icon, tint = scheme.primary.copy(alpha = 0.75f), size = 26.dp)
            }
            Spacer(Modifier.height(16.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = sem.textTertiary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            action()
        }
    }
}

/**
 * 顶栏:浮在内容上的玻璃胶囊,左边圆钮、中间标题、右边圆钮。
 *
 * 不用 Material 的 TopAppBar:它有不透明底色和 elevation,和渐变背景打架。
 */
@Composable
fun GlassTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val sem = LocalDshSemantic.current
    GlassSurface(
        tier = GlassTier.Chrome,
        shape = RoundedCornerShape(AppShape.xl),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) leading()
            Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = sem.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (actions != null) actions()
        }
    }
}

/** 顶栏上的圆钮(玻璃 + 图标),与 [GlassTopBar] 配套。 */
@Composable
fun GlassIconButton(
    icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    contentDescription: String? = null,
) {
    GlassSurface(
        tier = GlassTier.Chrome,
        shape = CircleShape,
        modifier = modifier.size(40.dp),
        onClick = onClick,
    ) {
        // fillMaxSize:玻璃内容是左上对齐的,只写 fillMaxWidth 会让图标贴在圆钮顶部。
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            DshIcon(icon, tint = tint, size = 19.dp)
        }
    }
}
