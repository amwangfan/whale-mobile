import 'package:flutter/material.dart';

/// Shared navigation state for the app shell and notification deep links.
class AppNavigation {
  AppNavigation._();

  static final GlobalKey<NavigatorState> navigatorKey =
      GlobalKey<NavigatorState>();
  static final ValueNotifier<int> tabIndex = ValueNotifier<int>(0);

  static void switchTab(int index) => tabIndex.value = index;
}
